// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.openapi.vcs;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.CommandEvent;
import com.intellij.openapi.command.CommandListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsIgnoreManager;
import com.intellij.openapi.vcs.changes.ignore.IgnoreFilesProcessorImpl;
import com.intellij.openapi.vfs.AsyncFileListener;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.SmartHashSet;
import com.intellij.vcsUtil.VcsUtil;
import com.intellij.vfs.AsyncVfsEventsListener;
import com.intellij.vfs.AsyncVfsEventsPostProcessor;
import kotlin.Unit;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.Collections.emptyMap;

public abstract class VcsVFSListener implements Disposable {
  protected static final Logger LOG = Logger.getInstance(VcsVFSListener.class);

  private final ProjectLevelVcsManager myVcsManager;
  private final VcsIgnoreManager myVcsIgnoreManager;
  private final VcsFileListenerContextHelper myVcsFileListenerContextHelper;

  protected static class MovedFileInfo {
    @NotNull
    public final String myOldPath;
    @NotNull
    public String myNewPath;
    @NotNull
    private final VirtualFile myFile;

    MovedFileInfo(@NotNull VirtualFile file, @NotNull String newPath) {
      myOldPath = file.getPath();
      myNewPath = newPath;
      myFile = file;
    }

    @Override
    public String toString() {
      return String.format("MovedFileInfo{[%s] -> [%s]}", myOldPath, myNewPath);
    }
  }

  protected static class AllDeletedFiles {
    public final List<FilePath> deletedFiles;
    public final List<FilePath> deletedWithoutConfirmFiles;

    public AllDeletedFiles(@NotNull List<FilePath> deletedFiles, @NotNull List<FilePath> deletedWithoutConfirmFiles) {
      this.deletedFiles = deletedFiles;
      this.deletedWithoutConfirmFiles = deletedWithoutConfirmFiles;
    }
  }

  protected final Project myProject;
  protected final AbstractVcs myVcs;
  protected final ChangeListManager myChangeListManager;
  private final VcsShowConfirmationOption myAddOption;
  protected final VcsShowConfirmationOption myRemoveOption;
  protected final StateProcessor myProcessor = new StateProcessor();
  private final ProjectConfigurationFilesProcessorImpl myProjectConfigurationFilesProcessor;
  protected final ExternallyAddedFilesProcessorImpl myExternalFilesProcessor;

  protected enum VcsDeleteType {SILENT, CONFIRM, IGNORE}

  protected final class StateProcessor {
    private final Set<VirtualFile> myAddedFiles = new SmartHashSet<>();
    private final Map<VirtualFile, VirtualFile> myCopyFromMap = new HashMap<>();
    private final Set<FilePath> myDeletedFiles = new SmartHashSet<>();
    private final Set<FilePath> myDeletedWithoutConfirmFiles = new SmartHashSet<>();
    private final Set<MovedFileInfo> myMovedFiles = new SmartHashSet<>();
    private final List<VcsException> myExceptions = new SmartList<>();

    private final ReentrantReadWriteLock PROCESSING_LOCK = new ReentrantReadWriteLock();

    public boolean addException(@NotNull VcsException exception) {
      return computeUnderLock(PROCESSING_LOCK.writeLock(), () -> myExceptions.add(exception));
    }

    @NotNull
    public List<VcsException> acquireExceptions() {
      return acquireListUnderLock(myExceptions);
    }

    @NotNull
    public List<VirtualFile> acquireAddedFiles() {
      return acquireListUnderLock(myAddedFiles);
    }

    @NotNull
    public List<MovedFileInfo> acquireMovedFiles() {
      return acquireListUnderLock(myMovedFiles);
    }

    @NotNull
    public AllDeletedFiles acquireAllDeletedFiles() {
      return computeUnderLock(PROCESSING_LOCK.writeLock(), () -> {
        List<FilePath> deletedWithoutConfirmFiles = new ArrayList<>(myDeletedWithoutConfirmFiles);
        List<FilePath> deletedFiles = new ArrayList<>(myDeletedFiles);
        myDeletedWithoutConfirmFiles.clear();
        myDeletedFiles.clear();
        return new AllDeletedFiles(deletedFiles, deletedWithoutConfirmFiles);
      });
    }

    /**
     * @return get a list of files under lock and clear the given collection of files
     */
    @NotNull
    private <T> List<T> acquireListUnderLock(@NotNull Collection<T> files) {
      return computeUnderLock(PROCESSING_LOCK.writeLock(), () -> {
        List<T> copiedFiles = new ArrayList<>(files);
        files.clear();
        return copiedFiles;
      });
    }

    /**
     * @return get a map of copied files under lock and clear the given map
     */
    @NotNull
    public Map<VirtualFile, VirtualFile> acquireCopiedFiles() {
      return computeUnderLock(PROCESSING_LOCK.writeLock(), () -> {
        Map<VirtualFile, VirtualFile> copyFromMap = new HashMap<>(myCopyFromMap);
        myCopyFromMap.clear();
        return copyFromMap;
      });
    }

    private void checkMovedAddedSourceBack() {
      if (myAddedFiles.isEmpty() || myMovedFiles.isEmpty()) return;

      Map<String, VirtualFile> addedPaths = new HashMap<>(myAddedFiles.size());
      for (VirtualFile file : myAddedFiles) {
        addedPaths.put(file.getPath(), file);
      }

      for (Iterator<MovedFileInfo> iterator = myMovedFiles.iterator(); iterator.hasNext(); ) {
        MovedFileInfo movedFile = iterator.next();
        if (addedPaths.containsKey(movedFile.myOldPath)) {
          iterator.remove();
          VirtualFile oldAdded = addedPaths.get(movedFile.myOldPath);
          myAddedFiles.remove(oldAdded);
          myAddedFiles.add(movedFile.myFile);
          myCopyFromMap.put(oldAdded, movedFile.myFile);
        }
      }
    }

    // If a file is scheduled for deletion, and at the same time for copying or addition, don't delete it.
    // It happens during Overwrite command or undo of overwrite.
    private void doNotDeleteAddedCopiedOrMovedFiles() {
      Collection<String> copiedAddedMoved = new ArrayList<>();
      for (VirtualFile file : myCopyFromMap.keySet()) {
        copiedAddedMoved.add(file.getPath());
      }
      for (VirtualFile file : myAddedFiles) {
        copiedAddedMoved.add(file.getPath());
      }
      for (MovedFileInfo movedFileInfo : myMovedFiles) {
        copiedAddedMoved.add(movedFileInfo.myNewPath);
      }

      myDeletedFiles.removeIf(path -> copiedAddedMoved.contains(FileUtil.toSystemIndependentName(path.getPath())));
      myDeletedWithoutConfirmFiles.removeIf(path -> copiedAddedMoved.contains(FileUtil.toSystemIndependentName(path.getPath())));
    }

    @CalledInAwt
    private void process() {
      runUnderLock(PROCESSING_LOCK.writeLock(), () -> {
        if (!myAddedFiles.isEmpty() || !myDeletedFiles.isEmpty() || !myDeletedWithoutConfirmFiles.isEmpty() || !myMovedFiles.isEmpty()) {
          doNotDeleteAddedCopiedOrMovedFiles();
          checkMovedAddedSourceBack();
          if (!myAddedFiles.isEmpty()) {
            executeAdd();
            myAddedFiles.clear();
          }
          if (!myDeletedFiles.isEmpty() || !myDeletedWithoutConfirmFiles.isEmpty()) {
            executeDelete();
            myDeletedFiles.clear();
            myDeletedWithoutConfirmFiles.clear();
          }
          if (!myMovedFiles.isEmpty()) {
            executeMoveRename();
            myMovedFiles.clear();
          }
        }
      });

      List<VcsException> exceptions = acquireExceptions();
      if (!exceptions.isEmpty()) {
        AbstractVcsHelper.getInstance(myProject).showErrors(exceptions, myVcs.getDisplayName() + " operations errors");
      }
    }

    private void processFileCreated(@NotNull VFileCreateEvent event) {
      VirtualFile file = event.getFile();
      if (file == null) return;

      LOG.debug("fileCreated: ", file);
      if (isDirectoryVersioningSupported() || !file.isDirectory()) {
        LOG.debug("Adding [", file, "] to added files");
        runUnderLock(PROCESSING_LOCK.writeLock(), () -> {
          myAddedFiles.add(file);
        });
      }
    }

    private void processFileMoved(@NotNull VFileMoveEvent event) {
      VirtualFile file = event.getFile();
      VirtualFile oldParent = event.getOldParent();
      if (!isUnderMyVcs(oldParent)) {
        runUnderLock(PROCESSING_LOCK.writeLock(), () -> {
          myAddedFiles.add(file);
        });
      }
    }

    private void processFileCopied(@NotNull VFileCopyEvent event) {
      VirtualFile newFile = event.getNewParent().findChild(event.getNewChildName());
      if (newFile == null || myChangeListManager.isIgnoredFile(newFile)) return;
      VirtualFile originalFile = event.getFile();
      runUnderLock(PROCESSING_LOCK.writeLock(), () -> {
        if (isUnderMyVcs(originalFile)) {
          myAddedFiles.add(newFile);
          myCopyFromMap.put(newFile, originalFile);
        }
        else {
          myAddedFiles.add(newFile);
        }
      });
    }

    private void processDeletedFile(@NotNull VirtualFile file) {
      if (file.isDirectory() && file instanceof NewVirtualFile && !isDirectoryVersioningSupported()) {
        for (VirtualFile child : ((NewVirtualFile)file).getCachedChildren()) {
          processDeletedFile(child);
        }
      }
      else {
        VcsDeleteType type = needConfirmDeletion(file);
        FilePath filePath = VcsUtil.getFilePath(file);
        runUnderLock(PROCESSING_LOCK.writeLock(), () -> {
          if (type == VcsDeleteType.CONFIRM) {
            myDeletedFiles.add(filePath);
          }
          else if (type == VcsDeleteType.SILENT) {
            myDeletedWithoutConfirmFiles.add(filePath);
          }
        });
      }
    }

    private void processMovedFile(@NotNull VirtualFile file, @NotNull String newParentPath, @NotNull String newName) {
      FileStatus status = myChangeListManager.getStatus(file);
      LOG.debug("Checking moved file ", file, "; status=", status);

      String newPath = newParentPath + "/" + newName;
      runUnderLock(PROCESSING_LOCK.writeLock(), () -> {
        if (!(filterOutUnknownFiles() && status == FileStatus.UNKNOWN) && status != FileStatus.IGNORED) {
          MovedFileInfo existingMovedFile = ContainerUtil.find(myMovedFiles, info -> Comparing.equal(info.myFile, file));
          if (existingMovedFile != null) {
            LOG.debug("Reusing existing moved file [" + file + "] with new path [" + newPath + "]");
            existingMovedFile.myNewPath = newPath;
          }
          else {
            LOG.debug("Registered moved file ", file);
            myMovedFiles.add(new MovedFileInfo(file, newPath));
          }
        }
        else {
          // If a file is moved on top of another file (overwrite), the VFS at first removes the original file,
          // and then performs the "clean" move.
          // But we don't need to handle this deletion by the VCS: it is not a real deletion, but just a trick to implement the overwrite.
          // This situation is already handled in doNotDeleteAddedCopiedOrMovedFiles(), but that method is called at the end of the command,
          // so it is not suitable for moving unversioned files: if an unversioned file is moved, it won't be recorded,
          // won't affect doNotDeleteAddedCopiedOrMovedFiles(), and therefore won't save the file from deletion.
          // Thus here goes a special handle for unversioned files overwrite-move.
          myDeletedFiles.remove(VcsUtil.getFilePath(newPath));
        }
      });
    }

    private void processBeforeFileDeletion(@NotNull VFileDeleteEvent event) {
      VirtualFile file = event.getFile();
      // files are checked for being ignored, directories are handled recursively
      if (!file.isDirectory()) {
        ProgressManager.checkCanceled();
        if (!myChangeListManager.isIgnoredFile(file)) {
          myProcessor.processDeletedFile(file);
        }
      }
      else {
        List<VirtualFile> list = new ArrayList<>();
        VcsUtil.collectFiles(file, list, true, isDirectoryVersioningSupported());
        for (VirtualFile child : list) {
          ProgressManager.checkCanceled();
          if (!myChangeListManager.isIgnoredFile(child)) {
            myProcessor.processDeletedFile(child);
          }
        }
      }
    }

    private void processBeforeFileMovement(@NotNull VFileMoveEvent event) {
      VirtualFile file = event.getFile();
      AbstractVcs newVcs = ReadAction.compute(() -> !myProject.isDisposed() ?
                                                    myVcsManager.getVcsFor(event.getNewParent()) : null);
      LOG.debug("beforeFileMovement ", event, " into ", newVcs);
      if (newVcs == myVcs) {
        addFileToMove(file, event.getNewParent().getPath(), file.getName());
      }
      else {
        myProcessor.processDeletedFile(event.getFile());
      }
    }

    private void processBeforePropertyChange(@NotNull VFilePropertyChangeEvent event) {
      if (event.isRename()) {
        LOG.debug("before file rename ", event);
        String newName = (String)event.getNewValue();
        VirtualFile file = event.getFile();
        VirtualFile parent = file.getParent();
        if (parent != null) {
          addFileToMove(file, parent.getPath(), newName);
        }
      }
    }
  }

  /**
   * @see #installListeners()
   */
  protected VcsVFSListener(@NotNull AbstractVcs vcs) {
    myProject = vcs.getProject();
    myVcs = vcs;
    myChangeListManager = ChangeListManager.getInstance(myProject);
    myVcsIgnoreManager = VcsIgnoreManager.getInstance(myProject);

    myVcsManager = ProjectLevelVcsManager.getInstance(myProject);
    myAddOption = myVcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, vcs);
    myRemoveOption = myVcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, vcs);

    myVcsFileListenerContextHelper = VcsFileListenerContextHelper.getInstance(myProject);

    myProjectConfigurationFilesProcessor = createProjectConfigurationFilesProcessor();
    myExternalFilesProcessor = createExternalFilesProcessor();
  }

  /**
   * @deprecated Use {@link #VcsVFSListener(AbstractVcs)} followed by {@link #installListeners()}
   */
  @Deprecated
  protected VcsVFSListener(@NotNull Project project, @NotNull AbstractVcs vcs) {
    this(vcs);
    installListeners();
  }

  protected void installListeners() {
    MyVirtualFileListener listener = new MyVirtualFileListener();
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      myProject.getMessageBus().connect(myProject).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
        @Override
        public void after(@NotNull List<? extends VFileEvent> events) {
          listener.filesChanged(events);
        }
      });
    }
    else {
      AsyncVfsEventsPostProcessor.getInstance().addListener(listener, this);
    }
    VirtualFileManager.getInstance().addAsyncFileListener(new MyAsyncVfsListener(), this);
    myProject.getMessageBus().connect(this).subscribe(CommandListener.TOPIC, new MyCommandAdapter());

    myProjectConfigurationFilesProcessor.install();
    myExternalFilesProcessor.install();
    new IgnoreFilesProcessorImpl(myProject, myVcs, this).install();
  }

  @Override
  public void dispose() {
  }

  protected boolean isEventIgnored(@NotNull VFileEvent event) {
    FilePath filePath = VcsUtil.getFilePath(event.getPath());
    if (event.isFromRefresh()) return true;
    return !isUnderMyVcs(filePath) && !myVcsIgnoreManager.isPotentiallyIgnoredFile(filePath);
  }

  protected boolean isUnderMyVcs(@Nullable VirtualFile file) {
    return file != null && ReadAction.compute(() -> !myProject.isDisposed() && myVcsManager.getVcsFor(file) == myVcs);
  }

  protected boolean isUnderMyVcs(@Nullable FilePath filePath) {
    return filePath != null && ReadAction.compute(() -> !myProject.isDisposed() && myVcsManager.getVcsFor(filePath) == myVcs);
  }

  @CalledInAwt
  protected void executeAdd() {
    List<VirtualFile> addedFiles = myProcessor.acquireAddedFiles();
    LOG.debug("executeAdd. addedFiles: ", addedFiles);
    addedFiles.removeIf(myVcsFileListenerContextHelper::isAdditionIgnored);
    Map<VirtualFile, VirtualFile> copyFromMap = myProcessor.acquireCopiedFiles();
    if (!addedFiles.isEmpty()) {
      executeAdd(addedFiles, copyFromMap);
    }
  }

  /**
   * Execute add that performs adding from specific collections
   *
   * @param addedFiles  the added files
   * @param copyFromMap the copied files
   */
  protected void executeAdd(@NotNull List<VirtualFile> addedFiles, @NotNull Map<VirtualFile, VirtualFile> copyFromMap) {
    LOG.debug("executeAdd. add-option: ", myAddOption.getValue(), ", files to add: ", addedFiles);
    if (myAddOption.getValue() == VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY) return;

    addedFiles = myProjectConfigurationFilesProcessor.filterNotProjectConfigurationFiles(addedFiles);

    if (myAddOption.getValue() == VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY) {
      performAdding(addedFiles, copyFromMap);
    }
    else {
      final AbstractVcsHelper helper = AbstractVcsHelper.getInstance(myProject);
      // TODO[yole]: nice and clean description label
      Collection<VirtualFile> filesToProcess = helper.selectFilesToProcess(addedFiles, getAddTitle(), null,
                                                                           getSingleFileAddTitle(), getSingleFileAddPromptTemplate(),
                                                                           myAddOption);
      if (filesToProcess != null) {
        performAdding(new ArrayList<>(filesToProcess), copyFromMap);
      }
    }
  }

  protected void executeAddWithoutIgnores(@NotNull List<VirtualFile> addedFiles,
                                          @NotNull Map<VirtualFile, VirtualFile> copyFromMap,
                                          @NotNull ExecuteAddCallback executeAddCallback) {
    executeAddCallback.executeAdd(addedFiles, copyFromMap);
  }

  private void executeMoveRename() {
    List<MovedFileInfo> movedFiles = myProcessor.acquireMovedFiles();
    LOG.debug("executeMoveRename " + movedFiles);
    performMoveRename(movedFiles);
  }

  @CalledInAwt
  protected void executeDelete() {
    AllDeletedFiles allFiles = myProcessor.acquireAllDeletedFiles();
    List<FilePath> filesToDelete = allFiles.deletedWithoutConfirmFiles;
    List<FilePath> deletedFiles = allFiles.deletedFiles;

    filesToDelete.removeIf(myVcsFileListenerContextHelper::isDeletionIgnored);
    deletedFiles.removeIf(myVcsFileListenerContextHelper::isDeletionIgnored);

    if (deletedFiles.isEmpty() && filesToDelete.isEmpty()) return;

    if (myRemoveOption.getValue() != VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY) {
      if (myRemoveOption.getValue() == VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY || deletedFiles.isEmpty()) {
        filesToDelete.addAll(deletedFiles);
      }
      else {
        Collection<FilePath> filePaths = selectFilePathsToDelete(deletedFiles);
        if (filePaths != null) {
          filesToDelete.addAll(filePaths);
        }
      }
    }
    performDeletion(filesToDelete);
  }

  protected void processMovedFile(@NotNull VirtualFile file, @NotNull String newParentPath, @NotNull String newName) {
    myProcessor.processMovedFile(file, newParentPath, newName);
  }

  @FunctionalInterface
  protected interface ExecuteAddCallback {
    void executeAdd(@NotNull List<VirtualFile> addedFiles, @NotNull Map<VirtualFile, VirtualFile> copyFromMap);
  }

  protected void saveUnsavedVcsIgnoreFiles() {
    FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
    Set<String> ignoreFileNames = VcsUtil.getVcsIgnoreFileNames(myProject);

    for (Document document : fileDocumentManager.getUnsavedDocuments()) {
      VirtualFile file = fileDocumentManager.getFile(document);
      if (file != null && ignoreFileNames.contains(file.getName())) {
        fileDocumentManager.saveDocument(document);
      }
    }
  }

  /**
   * Select file paths to delete
   *
   * @param deletedFiles deleted files set
   * @return selected files or null (that is considered as empty file set)
   */
  @Nullable
  protected Collection<FilePath> selectFilePathsToDelete(@NotNull List<FilePath> deletedFiles) {
    AbstractVcsHelper helper = AbstractVcsHelper.getInstance(myProject);
    return helper.selectFilePathsToProcess(deletedFiles, getDeleteTitle(), null, getSingleFileDeleteTitle(),
                                           getSingleFileDeletePromptTemplate(), myRemoveOption);
  }

  protected void beforeContentsChange(@NotNull VFileContentChangeEvent event, @NotNull VirtualFile file) {
  }

  private void addFileToMove(@NotNull VirtualFile file, @NotNull String newParentPath, @NotNull String newName) {
    if (file.isDirectory() && !file.is(VFileProperty.SYMLINK) && !isDirectoryVersioningSupported()) {
      @SuppressWarnings("UnsafeVfsRecursion") VirtualFile[] children = file.getChildren();
      if (children != null) {
        for (VirtualFile child : children) {
          ProgressManager.checkCanceled();
          addFileToMove(child, newParentPath + "/" + newName, child.getName());
        }
      }
    }
    else {
      processMovedFile(file, newParentPath, newName);
    }
  }

  protected boolean filterOutUnknownFiles() {
    return true;
  }

  @NotNull
  protected VcsDeleteType needConfirmDeletion(@NotNull VirtualFile file) {
    return VcsDeleteType.CONFIRM;
  }

  @NotNull
  protected abstract String getAddTitle();

  @NotNull
  protected abstract String getSingleFileAddTitle();

  @NotNull
  protected abstract String getSingleFileAddPromptTemplate();

  protected abstract void performAdding(@NotNull Collection<VirtualFile> addedFiles, @NotNull Map<VirtualFile, VirtualFile> copyFromMap);

  @NotNull
  protected abstract String getDeleteTitle();

  protected abstract String getSingleFileDeleteTitle();

  protected abstract String getSingleFileDeletePromptTemplate();

  protected abstract void performDeletion(@NotNull List<FilePath> filesToDelete);

  protected abstract void performMoveRename(@NotNull List<MovedFileInfo> movedFiles);

  protected abstract boolean isDirectoryVersioningSupported();

  @SuppressWarnings("unchecked")
  private ExternallyAddedFilesProcessorImpl createExternalFilesProcessor() {
    return new ExternallyAddedFilesProcessorImpl(myProject,
                                                 this,
                                                 myVcs,
                                                 (files) -> {
                                                   performAdding((Collection<VirtualFile>)files, emptyMap());
                                                   return Unit.INSTANCE;
                                                 });
  }

  @SuppressWarnings("unchecked")
  private ProjectConfigurationFilesProcessorImpl createProjectConfigurationFilesProcessor() {
    return new ProjectConfigurationFilesProcessorImpl(myProject,
                                                      this,
                                                      myVcs.getDisplayName(),
                                                      (files) -> {
                                                        performAdding((Collection<VirtualFile>)files, emptyMap());
                                                        return Unit.INSTANCE;
                                                      });
  }

  private class MyVirtualFileListener implements AsyncVfsEventsListener {

    @Override
    public void filesChanged(@NotNull List<? extends VFileEvent> events) {
      doProcessEvents(events);
    }

    private void doProcessEvents(@NotNull List<? extends VFileEvent> events) {
      for (VFileEvent event : events) {
        if (isEventIgnored(event)) continue;

        if (event instanceof VFileCreateEvent) {
          myProcessor.processFileCreated((VFileCreateEvent)event);
        }
        else if (event instanceof VFileCopyEvent) {
          myProcessor.processFileCopied((VFileCopyEvent)event);
        }
        else if (event instanceof VFileMoveEvent) {
          myProcessor.processFileMoved((VFileMoveEvent)event);
        }
      }
    }
  }

  private class MyAsyncVfsListener implements AsyncFileListener {

    @Nullable
    @Override
    public ChangeApplier prepareChange(@NotNull List<? extends VFileEvent> events) {
      List<VFileEvent> filtered = new ArrayList<>();
      for (VFileEvent event : events) {
        ProgressManager.checkCanceled();
        if (event instanceof VFileContentChangeEvent) {
          VirtualFile file = Objects.requireNonNull(event.getFile());
          if (isUnderMyVcs(file)) {
            filtered.add(event);
          }
        }
        else if (!isEventIgnored(event)) {
          filtered.add(event);
        }
      }
      return filtered.isEmpty() ? null : new ChangeApplier() {
        @Override
        public void beforeVfsChange() {
          for (VFileEvent event : filtered) {
            if (event instanceof VFileContentChangeEvent) {
              VirtualFile file = event.getFile();
              if (file == null) continue;

              beforeContentsChange((VFileContentChangeEvent)event, file);
            }
            else if (event instanceof VFileDeleteEvent) {
              myProcessor.processBeforeFileDeletion((VFileDeleteEvent)event);
            }
            else if (event instanceof VFileMoveEvent) {
              myProcessor.processBeforeFileMovement((VFileMoveEvent)event);
            }
            else if (event instanceof VFilePropertyChangeEvent) {
              myProcessor.processBeforePropertyChange((VFilePropertyChangeEvent)event);
            }
          }
        }
      };
    }
  }
    private class MyCommandAdapter implements CommandListener {

      @Override
      public void commandFinished(@NotNull CommandEvent event) {
        if (myProject != event.getProject()) return;

        myProcessor.process();
      }
    }

    private static void runUnderLock(@NotNull Lock lock, @NotNull Runnable runnable) {
      computeUnderLock(lock, (Computable<Void>)() -> {
        runnable.run();
        return null;
      });
    }

    private static <V> V computeUnderLock(@NotNull Lock lock, @NotNull Computable<V> computable) {
      lock.lock();
      try {
        return computable.compute();
      }
      finally {
        lock.unlock();
      }
    }
}

