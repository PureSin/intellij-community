package org.jetbrains.jps.android;

import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.SdkManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.Processor;
import com.intellij.util.containers.HashSet;
import org.jetbrains.android.sdk.MessageBuildingSdkLog;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.android.util.AndroidCompilerMessageKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.*;
import org.jetbrains.jps.idea.Facet;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Eugene.Kudelevsky
 */
class AndroidJpsUtil {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.jps.android.AndroidJpsUtil");

  private AndroidJpsUtil() {
  }

  @Nullable
  public static AndroidFacet getFacet(@NotNull Module module) {
    AndroidFacet androidFacet = null;

    for (Facet facet : module.getFacets().values()) {
      if (facet instanceof AndroidFacet) {
        androidFacet = (AndroidFacet)facet;
      }
    }
    return androidFacet;
  }

  @NotNull
  public static String[] toPaths(@NotNull File[] files) {
    final String[] result = new String[files.length];

    for (int i = 0; i < result.length; i++) {
      result[i] = files[i].getPath();
    }
    return result;
  }

  @Nullable
  public static File getOutputDirectoryForPackagedFiles(@NotNull ProjectPaths paths, @NotNull Module module) {
    // todo: return build directory for mavenized modules to place .dex and .apk files into target dir (not target/classes)
    return paths.getModuleOutputDir(module, false);
  }

  public static void addSubdirectories(@NotNull File baseDir, @NotNull Collection<String> result) {
    // only include files inside packages
    final File[] children = baseDir.listFiles();

    if (children != null) {
      for (File child : children) {
        if (child.isDirectory()) {
          result.add(child.getPath());
        }
      }
    }
  }

  @NotNull
  public static Set<String> getExternalLibraries(@NotNull ProjectPaths paths, @NotNull Module module) {
    final Set<String> result = new HashSet<String>();
    fillClasspath(paths, module, null, result, new HashSet<String>(), false);
    return result;
  }

  @NotNull
  public static Set<String> getClassdirsOfDependentModules(@NotNull ProjectPaths paths, @NotNull Module module) {
    final Set<String> result = new HashSet<String>();
    fillClasspath(paths, module, result, null, new HashSet<String>(), false);
    return result;
  }

  private static void fillClasspath(@NotNull ProjectPaths paths,
                                    @NotNull final Module module,
                                    @Nullable final Set<String> outputDirs,
                                    @Nullable final Set<String> libraries,
                                    @NotNull final Set<String> visitedModules,
                                    final boolean exportedLibrariesOnly) {
    if (!visitedModules.add(module.getName())) {
      return;
    }

    if (libraries != null) {
      for (ClasspathItem item : module.getClasspath(ClasspathKind.PRODUCTION_COMPILE, exportedLibrariesOnly)) {
        if (item instanceof Library && !(item instanceof Sdk)) {
          for (String filePath : item.getClasspathRoots(ClasspathKind.PRODUCTION_COMPILE)) {
            final File file = new File(filePath);

            if (file.exists()) {
              collectClassFilesAndJars(filePath, libraries);
            }
          }
        }
      }
    }

    for (ClasspathItem item : module.getClasspath(ClasspathKind.PRODUCTION_COMPILE, false)) {
      if (item instanceof Module) {
        final Module depModule = (Module)item;
        final AndroidFacet depFacet = getFacet(depModule);
        final boolean depLibrary = depFacet != null && depFacet.isLibrary();
        final File depClassDir = paths.getModuleOutputDir(depModule, false);

        if (outputDirs != null && depClassDir != null) {
          if (depLibrary) {
            final File packagedClassesJar = new File(depClassDir, AndroidCommonUtils.CLASSES_JAR_FILE_NAME);

            if (packagedClassesJar.isDirectory()) {
              outputDirs.add(packagedClassesJar.getPath());
            }
          }
          else if (depFacet == null && depClassDir.isDirectory()) {
            // do not support android-app->android-app compile dependencies
            outputDirs.add(depClassDir.getPath());
          }
        }
        fillClasspath(paths, depModule, outputDirs, libraries, visitedModules, !depLibrary || exportedLibrariesOnly);
      }
    }
  }

  private static void collectClassFilesAndJars(@NotNull String root,
                                               @NotNull final Set<String> result) {
    FileUtil.processFilesRecursively(new File(root), new Processor<File>() {
      @Override
      public boolean process(File file) {
        if (file.isFile()) {
          final String ext = FileUtil.getExtension(file.getName());

          if ("jar".equals(ext) || "class".equals(ext)) {
            result.add(file.getPath());
          }
        }
        return true;
      }
    });
  }

  @Nullable
  public static IAndroidTarget parseAndroidTarget(@NotNull AndroidSdk sdk, @NotNull CompileContext context, @NotNull String builderName) {
    final String targetHashString = sdk.getBuildTargetHashString();
    if (targetHashString == null) {
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR,
                                                 "Cannot parse SDK " + sdk.getName() + ": build target is not specified"));
      return null;
    }

    final MessageBuildingSdkLog log = new MessageBuildingSdkLog();
    final SdkManager manager = AndroidCommonUtils.createSdkManager(sdk.getSdkPath(), log);

    if (manager == null) {
      final String message = log.getErrorMessage();
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR,
                                                 "Android SDK is parsed incorrectly." +
                                                 (message.length() > 0 ? " Parsing log:\n" + message : "")));
      return null;
    }

    final IAndroidTarget target = manager.getTargetFromHashString(targetHashString);
    if (target == null) {
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR,
                                                 "Cannot parse SDK '" + sdk.getName() + "': unknown target " + targetHashString));
      return null;
    }
    return target;
  }

  public static void addMessages(@NotNull CompileContext context,
                                 @NotNull Map<AndroidCompilerMessageKind, List<String>> messages,
                                 @Nullable String sourcePath,
                                 @NotNull String builderName) {
    for (Map.Entry<AndroidCompilerMessageKind, List<String>> entry : messages.entrySet()) {
      final AndroidCompilerMessageKind kind = entry.getKey();
      final BuildMessage.Kind buildMessageKind = toBuildMessageKind(kind);

      if (buildMessageKind == null) {
        continue;
      }

      for (String message : entry.getValue()) {
        context.processMessage(new CompilerMessage(builderName, buildMessageKind, message, sourcePath));
      }
    }
  }

  @Nullable
  private static BuildMessage.Kind toBuildMessageKind(@NotNull AndroidCompilerMessageKind kind) {
    switch (kind) {
      case ERROR:
        return BuildMessage.Kind.ERROR;
      case INFORMATION:
        return BuildMessage.Kind.INFO;
      case WARNING:
        return BuildMessage.Kind.WARNING;
      default:
        LOG.error("unknown AndroidCompilerMessageKind object " + kind);
        return null;
    }
  }

  public static void reportExceptionError(@NotNull CompileContext context,
                                          @Nullable String filePath,
                                          @NotNull Exception exception,
                                          @NotNull String builderName) {
    final String message = exception.getMessage();

    if (message != null) {
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR, message, filePath));
      LOG.debug(exception);
    }
    else {
      context.processMessage(new CompilerMessage(builderName, exception));
    }
  }

  public static boolean containsAndroidFacet(@NotNull ModuleChunk chunk) {
    for (Module module : chunk.getModules()) {
      if (getFacet(module) != null) {
        return true;
      }
    }
    return false;
  }
}
