package org.jetbrains.idea.maven.compiler;

import com.intellij.compiler.CompilerConfigurationImpl;
import com.intellij.compiler.CompilerIOUtil;
import com.intellij.compiler.impl.CompilerUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.compiler.*;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.text.CaseInsensitiveStringHashingStrategy;
import gnu.trove.THashSet;
import org.apache.maven.model.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.dom.PropertyResolver;
import org.jetbrains.idea.maven.project.MavenProjectModel;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.utils.MavenLog;
import org.jetbrains.idea.maven.utils.MavenUtil;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Pattern;

public class MavenResourceCompiler implements ClassPostProcessingCompiler {
  private static final Key<List<String>> FILES_TO_DELETE_KEY = Key.create(MavenResourceCompiler.class.getSimpleName() + ".FILES_TO_DELETE");

  private Project myProject;
  private Map<String, Set<String>> myOutputItemsCache = new HashMap<String, Set<String>>();

  public MavenResourceCompiler(Project project) {
    myProject = project;
    loadCache();
  }

  private void loadCache() {
    File file = getCacheFile();
    if (!file.exists()) return;

    try {
      DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
      try {
        if (in.readInt() != CompilerConfigurationImpl.DEPENDENCY_FORMAT_VERSION) return;
        int modulesSize = in.readInt();
        HashMap<String, Set<String>> temp = new HashMap<String, Set<String>>();
        while (modulesSize-- > 0) {
          String module = CompilerIOUtil.readString(in);
          int pathsSize = in.readInt();
          Set<String> paths = createPathsSet(pathsSize);
          while (pathsSize-- > 0) {
            paths.add(CompilerIOUtil.readString(in));
          }
          temp.put(module, paths);
        }
        myOutputItemsCache = temp;
      }
      finally {
        in.close();
      }
    }
    catch (IOException e) {
      MavenLog.LOG.warn(e);
    }
  }

  private static Set<String> createPathsSet(int size) {
    return SystemInfo.isFileSystemCaseSensitive
           ? new THashSet<String>(size)
           : new THashSet<String>(size, CaseInsensitiveStringHashingStrategy.INSTANCE);
  }

  private void saveCache() {
    File file = getCacheFile();
    file.getParentFile().mkdirs();
    try {
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
      try {
        out.writeInt(CompilerConfigurationImpl.DEPENDENCY_FORMAT_VERSION);
        out.writeInt(myOutputItemsCache.size());
        for (Map.Entry<String, Set<String>> eachEntry : myOutputItemsCache.entrySet()) {
          String module = eachEntry.getKey();
          Set<String> paths = eachEntry.getValue();

          CompilerIOUtil.writeString(module, out);
          out.writeInt(paths.size());
          for (String eachPath : paths) {
            CompilerIOUtil.writeString(eachPath, out);
          }
        }
      }
      finally {
        out.close();
      }
    }
    catch (IOException e) {
      MavenLog.LOG.error(e);
    }
  }

  private File getCacheFile() {
    return new File(MavenUtil.getPluginSystemDir("Compiler"), myProject.getLocationHash());
  }

  public boolean validateConfiguration(CompileScope scope) {
    return true;
  }

  @NotNull
  public ProcessingItem[] getProcessingItems(final CompileContext context) {
    final MavenProjectsManager mavenProjectManager = MavenProjectsManager.getInstance(myProject);
    if (!mavenProjectManager.isMavenizedProject()) return ProcessingItem.EMPTY_ARRAY;

    return new ReadAction<ProcessingItem[]>() {
      protected void run(Result<ProcessingItem[]> resultObject) throws Throwable {
        List<ProcessingItem> itemsToProcess = new ArrayList<ProcessingItem>();
        List<String> filesToDelete = new ArrayList<String>();

        for (Module eachModule : context.getCompileScope().getAffectedModules()) {
          MavenProjectModel mavenProject = mavenProjectManager.findProject(eachModule);
          if (mavenProject == null) continue;

          Properties properties = loadFilters(context, mavenProject);
          long propertiesHashCode = calculateHashCode(properties);

          collectProcessingItems(eachModule, mavenProject, context, properties, propertiesHashCode, false, itemsToProcess);
          collectProcessingItems(eachModule, mavenProject, context, properties, propertiesHashCode, true, itemsToProcess);
          collectItemsToDelete(eachModule, itemsToProcess, filesToDelete);
        }
        if (!filesToDelete.isEmpty()) {
          itemsToProcess.add(new FakeProcessingItem());
        }
        context.putUserData(FILES_TO_DELETE_KEY, filesToDelete);
        resultObject.setResult(itemsToProcess.toArray(new ProcessingItem[itemsToProcess.size()]));
        removeObsoleteModulesFromCache();
      }
    }.execute().getResultObject();
  }

  private long calculateHashCode(Properties properties) {
    Set<String> sorted = new TreeSet<String>();
    for (Map.Entry<Object, Object> each : properties.entrySet()) {
      sorted.add(each.getKey().toString() + "->" + each.getValue().toString());
    }
    return sorted.hashCode();
  }

  private Properties loadFilters(CompileContext context, MavenProjectModel mavenProject) {
    Properties properties = new Properties();
    for (String each : mavenProject.getFilters()) {
      try {
        FileInputStream in = new FileInputStream(each);
        try {
          properties.load(in);
        }
        finally {
          in.close();
        }
      }
      catch (IOException e) {
        String url = VfsUtil.pathToUrl(mavenProject.getFile().getPath());
        context.addMessage(CompilerMessageCategory.WARNING, "Maven: Cannot read the filter. " + e.getMessage(), url, -1, -1);
      }
    }
    return properties;
  }

  private void collectProcessingItems(Module module,
                                      MavenProjectModel mavenProject,
                                      CompileContext context,
                                      Properties properties,
                                      long propertiesHashCode,
                                      boolean tests,
                                      List<ProcessingItem> result) {
    String outputDir = CompilerPaths.getModuleOutputPath(module, tests);
    if (outputDir == null) {
      context.addMessage(CompilerMessageCategory.ERROR, "Maven: Module '" + module.getName() + "'output is not specified", null, -1, -1);
      return;
    }

    List<Resource> resources = tests ? mavenProject.getTestResources() : mavenProject.getResources();
    for (Resource each : resources) {
      VirtualFile dir = LocalFileSystem.getInstance().findFileByPath(each.getDirectory());
      if (dir == null) continue;

      List<Pattern> includes = collectPatterns(each.getIncludes(), "**/*");
      List<Pattern> excludes = collectPatterns(each.getExcludes(), null);

      collectProcessingItems(module,
                             dir,
                             dir,
                             outputDir,
                             includes,
                             excludes,
                             each.isFiltering(),
                             properties,
                             propertiesHashCode,
                             result,
                             context.getProgressIndicator());
    }
  }

  private List<Pattern> collectPatterns(List<String> values, String defaultValue) {
    List<Pattern> result = new ArrayList<Pattern>();
    if (values == null || values.isEmpty()) {
      if (defaultValue == null) return Collections.emptyList();
      return MavenUtil.collectPattern(defaultValue, result);
    }
    for (String each : values) {
      MavenUtil.collectPattern(each, result);
    }
    return result;
  }

  private void collectProcessingItems(Module module,
                                      VirtualFile sourceRoot,
                                      VirtualFile currentDir,
                                      String outputDir,
                                      List<Pattern> includes,
                                      List<Pattern> excludes,
                                      boolean isSourceRootFiltered,
                                      Properties properties,
                                      long propertiesHashCode,
                                      List<ProcessingItem> result,
                                      ProgressIndicator indicator) {
    indicator.checkCanceled();

    for (VirtualFile eachSourceFile : currentDir.getChildren()) {
      if (eachSourceFile.isDirectory()) {
        collectProcessingItems(module,
                               sourceRoot,
                               eachSourceFile,
                               outputDir,
                               includes,
                               excludes,
                               isSourceRootFiltered,
                               properties,
                               propertiesHashCode,
                               result,
                               indicator);
      }
      else {
        String relPath = VfsUtil.getRelativePath(eachSourceFile, sourceRoot, '/');
        if (!MavenUtil.isIncluded(relPath, includes, excludes)) continue;

        String outputPath = outputDir + "/" + relPath;
        result.add(new MyProcessingItem(module, eachSourceFile, outputPath, isSourceRootFiltered, properties, propertiesHashCode));
      }
    }
  }

  private void collectItemsToDelete(Module module, List<ProcessingItem> processingItems, List<String> result) {
    Set<String> currentPaths = createPathsSet(processingItems.size());
    for (ProcessingItem each : processingItems) {
      if (each instanceof FakeProcessingItem) continue;
      currentPaths.add(((MyProcessingItem)each).getOutputPath());
    }

    Set<String> cachedPaths = myOutputItemsCache.get(module.getName());
    myOutputItemsCache.put(module.getName(), currentPaths);
    if (cachedPaths == null) {
      return;
    }

    Set<String> copy = new HashSet<String>(cachedPaths);
    copy.removeAll(currentPaths);
    result.addAll(copy);
  }

  private void removeObsoleteModulesFromCache() {
    Set<String> existingModules = new HashSet<String>();
    for (Module each : ModuleManager.getInstance(myProject).getModules()) {
      existingModules.add(each.getName());
    }

    for (String each : new HashSet<String>(myOutputItemsCache.keySet())) {
      if (!existingModules.contains(each)) {
        myOutputItemsCache.remove(each);
      }
    }
  }

  public ProcessingItem[] process(final CompileContext context, ProcessingItem[] items) {
    context.getProgressIndicator().setText("Processing Maven resources...");

    List<ProcessingItem> result = new ArrayList<ProcessingItem>(items.length);
    List<File> filesToRefresh = new ArrayList<File>(items.length);

    deleteOutdatedFile(context.getUserData(FILES_TO_DELETE_KEY), filesToRefresh);

    int count = 0;
    for (final ProcessingItem each : items) {
      if (each instanceof FakeProcessingItem) continue;

      context.getProgressIndicator().setFraction(((double)count) / items.length);
      context.getProgressIndicator().checkCanceled();

      MyProcessingItem eachItem = (MyProcessingItem)each;
      VirtualFile sourceVirtualFile = eachItem.getFile();
      File sourceFile = new File(sourceVirtualFile.getPath());
      File outputFile = new File(eachItem.getOutputPath());

      try {
        outputFile.getParentFile().mkdirs();

        if (eachItem.isFiltered()) {
          String charset = getCharsetName(sourceVirtualFile);
          String text = new String(FileUtil.loadFileBytes(sourceFile), charset);

          text = PropertyResolver.resolve(eachItem.getModule(), text, eachItem.getProperties());
          FileUtil.writeToFile(outputFile, text.getBytes(charset));
        }
        else {
          // TODO
          //boolean wasFiltered = outputFile.getTimeStamp() != sourceFile.getTimeStamp();
          //if (wasFiltered) {
          //  FileUtil.copy(new File(sourceFile.getPath()), new File(outputFile.getPath()));
          //}
          FileUtil.copy(sourceFile, outputFile);
        }
        result.add(each);
        filesToRefresh.add(outputFile);
      }
      catch (IOException e) {
        context.addMessage(CompilerMessageCategory.ERROR,
                           "Maven: Cannot process resource file: " + e.getMessage(),
                           sourceVirtualFile.getUrl(),
                           -1,
                           -1);
      }
    }
    CompilerUtil.refreshIOFiles(filesToRefresh);
    saveCache();
    return result.toArray(new ProcessingItem[result.size()]);
  }

  private void deleteOutdatedFile(List<String> filesToDelete, List<File> filesToRefresh) {
    for (String each : filesToDelete) {
      File file = new File(each);
      if (FileUtil.delete(file)) {
        filesToRefresh.add(file);
      }
    }
  }

  private String getCharsetName(VirtualFile sourceFile) {
    EncodingManager manager = EncodingManager.getInstance();
    Charset charset;
    if (StdFileTypes.PROPERTIES.equals(sourceFile.getFileType())) {
      charset = manager.getDefaultCharsetForPropertiesFiles(sourceFile);
    }
    else {
      charset = manager.getEncoding(sourceFile, true);
    }
    if (charset == null) charset = manager.getDefaultCharset();
    return charset.name();
  }

  @NotNull
  public String getDescription() {
    return "Maven Resource Compiler";
  }

  public ValidityState createValidityState(DataInput in) throws IOException {
    return MyValididtyState.load(in);
  }

  private static class MyProcessingItem implements ProcessingItem {
    private final Module myModule;
    private final VirtualFile mySourceFile;
    private final String myOutputPath;
    private final boolean myFiltered;
    private final Properties myProperties;
    private final MyValididtyState myState;

    public MyProcessingItem(Module module,
                            VirtualFile sourceFile,
                            String outputPath,
                            boolean isFiltered,
                            Properties properties,
                            long propertiesHashCode) {
      myModule = module;
      mySourceFile = sourceFile;
      myOutputPath = outputPath;
      myFiltered = isFiltered;
      myProperties = properties;
      myState = new MyValididtyState(mySourceFile, isFiltered, propertiesHashCode);
    }

    @NotNull
    public VirtualFile getFile() {
      return mySourceFile;
    }

    public String getOutputPath() {
      return myOutputPath;
    }

    public Module getModule() {
      return myModule;
    }

    public boolean isFiltered() {
      return myFiltered;
    }

    public Properties getProperties() {
      return myProperties;
    }

    public ValidityState getValidityState() {
      return myState;
    }
  }

  private static class FakeProcessingItem implements ProcessingItem {
    private LightVirtualFile myFile;

    private FakeProcessingItem() {
      myFile = new LightVirtualFile("fooBar");
    }

    @NotNull
    public VirtualFile getFile() {
      return myFile;
    }

    public ValidityState getValidityState() {
      return null;
    }
  }

  private static class MyValididtyState implements ValidityState {
    TimestampValidityState myTimestampState;
    private boolean myFiltered;
    private long myPropertiesHashCode;

    public static MyValididtyState load(DataInput in) throws IOException {
      return new MyValididtyState(TimestampValidityState.load(in), in.readBoolean(), in.readLong());
    }

    public MyValididtyState(VirtualFile file, boolean isFiltered, long propertiesHashCode) {
      this(new TimestampValidityState(file.getTimeStamp()), isFiltered, propertiesHashCode);
    }

    private MyValididtyState(TimestampValidityState timestampState, boolean isFiltered, long propertiesHashCode) {
      myTimestampState = timestampState;
      myFiltered = isFiltered;
      myPropertiesHashCode = propertiesHashCode;
    }

    public boolean equalsTo(ValidityState otherState) {
      if (!(otherState instanceof MyValididtyState)) return false;
      MyValididtyState state = (MyValididtyState)otherState;
      return myTimestampState.equalsTo(state.myTimestampState)
             && myFiltered == state.myFiltered
             && myPropertiesHashCode == state.myPropertiesHashCode;
    }

    public void save(DataOutput out) throws IOException {
      myTimestampState.save(out);
      out.writeBoolean(myFiltered);
      out.writeLong(myPropertiesHashCode);
    }
  }
}
