// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.serviceContainer

import com.intellij.diagnostic.ActivityCategory
import com.intellij.diagnostic.LoadingPhase
import com.intellij.diagnostic.PluginException
import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import org.picocontainer.ComponentAdapter
import org.picocontainer.PicoContainer
import org.picocontainer.PicoVisitor

internal abstract class BaseComponentAdapter(internal val componentManager: PlatformComponentManagerImpl,
                                             val pluginDescriptor: PluginDescriptor,
                                             @field:Volatile private var initializedInstance: Any?,
                                             private var implementationClass: Class<*>?) : ComponentAdapter {
  private var initializing = false

  final override fun verify(container: PicoContainer) {}

  final override fun accept(visitor: PicoVisitor) {
    visitor.visitComponentAdapter(this)
  }

  protected val pluginId: PluginId
    get() = pluginDescriptor.pluginId

  protected abstract val implementationClassName: String

  @Synchronized
  fun isImplementationClassResolved() = implementationClass != null

  final override fun getComponentImplementation() = getImplementationClass()

  @Synchronized
  fun getImplementationClass(): Class<*> {
    var result = implementationClass
    if (result == null) {
      val implClass: Class<*> = try {
        Class.forName(implementationClassName, true, pluginDescriptor.pluginClassLoader)
      }
      catch (e: ClassNotFoundException) {
        throw PluginException("Failed to load class: ${toString()}", e, pluginDescriptor.pluginId)
      }

      result = implClass
      implementationClass = result
    }
    return result
  }

  @Suppress("DeprecatedCallableAddReplaceWith")
  @Deprecated("Do not use")
  final override fun getComponentInstance(container: PicoContainer): Any? {
    //LOG.error("Use getInstance() instead")
    return getInstance(componentManager)
  }

  fun <T : Any> getInstance(componentManager: PlatformComponentManagerImpl, createIfNeeded: Boolean = true, indicator: ProgressIndicator? = null): T? {
    // could be called during some component.dispose() call, in this case we don't attempt to instantiate
    @Suppress("UNCHECKED_CAST")
    val instance = initializedInstance as T?
    if (instance != null || !createIfNeeded) {
      return instance
    }
    return getInstanceUncached(componentManager, indicator ?: ProgressManager.getGlobalProgressIndicator())
  }

  private fun <T : Any> getInstanceUncached(componentManager: PlatformComponentManagerImpl, indicator: ProgressIndicator?): T? {
    LoadingPhase.COMPONENT_REGISTERED.assertAtLeast()
    checkContainerIsActive(componentManager, indicator)

    synchronized(this) {
      @Suppress("UNCHECKED_CAST")
      var instance = initializedInstance as T?
      if (instance != null) {
        return instance
      }

      if (initializing) {
        LOG.error(PluginException("Cyclic service initialization: ${toString()}", pluginId))
      }

      try {
        initializing = true

        val startTime = StartUpMeasurer.getCurrentTime()
        @Suppress("UNCHECKED_CAST")
        val implementationClass = getImplementationClass() as Class<T>

        // check after loading class once again
        checkContainerIsActive(componentManager, indicator)

        instance = doCreateInstance(componentManager, implementationClass, indicator)
        getActivityCategory(componentManager)?.let { category ->
          StartUpMeasurer.addCompletedActivity(startTime, implementationClass, category, pluginId.idString)
        }

        initializedInstance = instance
        return instance
      }
      finally {
        initializing = false
      }
    }
  }

  private fun checkContainerIsActive(componentManager: PlatformComponentManagerImpl, indicator: ProgressIndicator?) {
    checkCanceledIfNotInClassInit()

    if (componentManager.isContainerDisposedOrDisposeInProgress()) {
      val error = PluginException("Cannot create ${toString()} because container is already disposed (container=${componentManager})", pluginId)
      if (indicator == null) {
        throw error
      }
      else {
        throw ProcessCanceledException(error)
      }
    }
  }

  protected abstract fun getActivityCategory(componentManager: PlatformComponentManagerImpl): ActivityCategory?

  protected abstract fun <T : Any> doCreateInstance(componentManager: PlatformComponentManagerImpl, implementationClass: Class<T>, indicator: ProgressIndicator?): T

  @Synchronized
  fun <T : Any> replaceInstance(instance: T, parentDisposable: Disposable?): T? {
    val old = initializedInstance
    initializedInstance = instance

    if (parentDisposable != null) {
      Disposer.register(parentDisposable, Disposable {
        synchronized(this) {
          if (initializedInstance === instance && instance is Disposable && !Disposer.isDisposed(instance)) {
            Disposer.dispose(instance)
          }
          initializedInstance = old
        }
      })
    }

    @Suppress("UNCHECKED_CAST")
    return old as T?
  }
}