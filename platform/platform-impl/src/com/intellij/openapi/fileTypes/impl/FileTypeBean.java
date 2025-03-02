// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.fileTypes.impl;

import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.extensions.RequiredElement;
import com.intellij.openapi.fileTypes.FileNameMatcher;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.serviceContainer.BaseKeyedLazyInstance;
import com.intellij.util.SmartList;
import com.intellij.util.xmlb.annotations.Attribute;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class FileTypeBean extends BaseKeyedLazyInstance<FileType> {
  private final List<FileNameMatcher> myMatchers = new SmartList<>();

  /**
   * Name of the class implementing the file type (must be a subclass of {@link FileType}). This can be omitted
   * if the fileType declaration is used to add extensions to an existing file type (in this case, only 'name'
   * and 'extensions' attributes must be specified).
   */
  @Attribute("implementationClass")
  public String implementationClass;

  /**
   * Name of the public static field in the implementationClass class containing the file type instance.
   */
  @Attribute("fieldName")
  public String fieldName;

  /**
   * Name of the file type. Needs to match the return value of {@link FileType#getName()}.
   */
  @Attribute("name")
  @RequiredElement
  @NonNls
  public String name;

  /**
   * Semicolon-separated list of extensions to be associated with the file type. Extensions
   * must not be prefixed with a `.`.
   */
  @Attribute("extensions")
  @NonNls
  public String extensions;

  /**
   * Semicolon-separated list of exact file names to be associated with the file type.
   */
  @Attribute("fileNames")
  @NonNls
  public String fileNames;

  /**
   * Semicolon-separated list of patterns (strings containing ? and * characters) to be associated with the file type.
   */
  @Attribute("patterns")
  @NonNls
  public String patterns;

  /**
   * Semicolon-separated list of exact file names (case-insensitive) to be associated with the file type.
   */
  @Attribute("fileNamesCaseInsensitive")
  @NonNls
  public String fileNamesCaseInsensitive;

  /**
   * For file types that extend {@link LanguageFileType} and are the primary file type for the corresponding language, this must be set
   * to the ID of the language returned by {@link LanguageFileType#getLanguage()}.
   */
  @Attribute("language")
  public String language;

  @ApiStatus.Internal
  public void addMatchers(List<? extends FileNameMatcher> matchers) {
    myMatchers.addAll(matchers);
  }

  @ApiStatus.Internal
  public List<FileNameMatcher> getMatchers() {
    return new ArrayList<>(myMatchers);
  }

  @Nullable
  @Override
  protected String getImplementationClassName() {
    return implementationClass;
  }

  @Nullable
  public PluginId getPluginId() {
    return getPluginDescriptor().getPluginId();
  }
}