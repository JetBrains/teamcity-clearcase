/*
 * Copyright 2000-2022 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.buildServer.vcs.clearcase;

import java.io.*;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jetbrains.buildServer.util.FileUtil;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CTool {

  public static final String CLEARTOOL_EXEC_PATH_ENV = "CLEARTOOL_PATH"; //$NON-NLS-1$
  public static final String CLEARTOOL_EXEC_PATH_PROP = "cleartool.path"; //$NON-NLS-1$

  private static final String CMD_DESCRIBE = Messages.getString("CTool.cmd_describe"); //$NON-NLS-1$
  private static final String CMD_LSVTREE = Messages.getString("CTool.cmd_lsvtree"); //$NON-NLS-1$
  private static final String CMD_DSCRVIEW_IN_FOLDER = Messages.getString("CTool.cmd_dscrview_inplace"); //$NON-NLS-1$
  private static final String CMD_DSCRVIEW_BY_TAG = Messages.getString("CTool.cmd_dscrview_bytag"); //$NON-NLS-1$
  private static final String CMD_LSVIEW = Messages.getString("CTool.cmd_lsview"); //$NON-NLS-1$
  private static final String CMD_LSLOCATIONS = Messages.getString("CTool.cmd_lsstgloc"); //$NON-NLS-1$
  private static final String CMD_LSVOB = Messages.getString("CTool.cmd_lsvob"); //$NON-NLS-1$
  private static final String CMD_LSHISTORY_CONTAINING = Messages.getString("CTool.cmd_lshistory"); //$NON-NLS-1$
  private static final String CMD_LSHISTORY_CONTAINER = Messages.getString("CTool.cmd_lshistory_inplace"); //$NON-NLS-1$
  private static final String CMD_LSHISTORY_CONTAINER_ALL = Messages.getString("CTool.cmd_lshistory_inplace_all"); //$NON-NLS-1$  
  private static final String CMD_LSCHANGE = Messages.getString("CTool.cmd_lschanges"); //$NON-NLS-1$
  private static final String CMD_UPDATE = Messages.getString("CTool.cmd_update"); //$NON-NLS-1$
  private static final String CMD_MKVIEW_AUTOLOC = Messages.getString("CTool.cmd_mkview_autoloc"); //$NON-NLS-1$
  private static final String CMD_MKVIEW_VWS = Messages.getString("CTool.cmd_mkview_vws"); //$NON-NLS-1$

  private static final String CMD_MKVIEW_AUTOLOC_UCM = Messages.getString("CTool.cmd_mkview_autoloc_ucm"); //$NON-NLS-1$
  private static final String CMD_MKVIEW_VWS_UCM = Messages.getString("CTool.cmd_mkview_vws_ucm"); //$NON-NLS-1$  

  private static final String CMD_RMVIEW = Messages.getString("CTool.cmd_rmview"); //$NON-NLS-1$
  private static final String CMD_RMVOB = Messages.getString("CTool.cmd_rmvob"); //$NON-NLS-1$
  private static final String CMD_SETCS = Messages.getString("CTool.cmd_setcs"); //$NON-NLS-1$
  private static final String CMD_RMVER = Messages.getString("CTool.cmd_rmver"); //$NON-NLS-1$
  private static final String CMD_RMELEM = Messages.getString("CTool.cmd_rmelem"); //$NON-NLS-1$
  private static final String CMD_RMNAME = Messages.getString("CTool.cmd_rmname"); //$NON-NLS-1$
  private static final String CMD_CHECKIN = Messages.getString("CTool.cmd_checkin"); //$NON-NLS-1$
  private static final String CMD_MKELEM = Messages.getString("CTool.cmd_mkelem"); //$NON-NLS-1$
  private static final String CMD_CHECKOUT = Messages.getString("CTool.cmd_checkout"); //$NON-NLS-1$
  private static final String CMD_CATCS = Messages.getString("CTool.cmd_catcs"); //$NON-NLS-1$

  private static final String CMD_LSSTREAM = "%s lsstream -long -view %s";

  private static final Logger LOG = Logger.getLogger(CTool.class);

  @SuppressWarnings("unused")
  private static String ourSessionUser;
  @SuppressWarnings("unused")
  private static String ourSessionPassword;
  private static String ourCleartoolExecutable = "cleartool"; //$NON-NLS-1$
  

  public static String getCleartoolExecutable() {
    return ourCleartoolExecutable;
  }

  public static void setCleartoolExecutable(final String executable) {
    if (executable != null) {
      ourCleartoolExecutable = executable.trim();
    } else {
      LOG.warn(String.format("Could not set CleartoolExecutable to \"null\""));
    }
  }
  
  //Executor's part
  public static interface ICommandExecutor {
    String[] execAndWait(@NotNull String command) throws IOException;
    String[] execAndWait(@NotNull String command, @NotNull File workingDirectory) throws IOException;
    String[] execAndWait(@NotNull String command, @NotNull String input, @NotNull File workingDirectory) throws IOException;
  }
  
  private static ICommandExecutor ourCommandExecutor = new ICommandExecutor() {
    public String[] execAndWait(final String command) throws IOException {
      return Util.execAndWait(command);
    }

    public String[] execAndWait(final String command, final File path) throws IOException {
      return Util.execAndWait(command, path);
    }

    public String[] execAndWait(@NotNull final String command, @NotNull final String input, @NotNull final File path) throws IOException {
      return Util.execAndWait(command, input, null, path);
    }
  };
  
  public static ICommandExecutor getCommandExecutor() {
    return ourCommandExecutor;
  }
  
  public static void setCommandExecutor(final @NotNull ICommandExecutor executor) {
    ourCommandExecutor = executor;
  }

  /**
   * the account uses for PsExec execution only
   */
  public static void setCredential(final String user, final String password) {
    ourSessionUser = user;
    ourSessionPassword = password;
  }
  
  static VobObjectParser createVob(final String tag, final String reason) throws IOException, InterruptedException {
    /**
     * check the Tag already exists
     */
    if (isVobExists(tag)) {
      throw new IOException(String.format("The VOB \"%s\" already exists", tag));
    }
    final String command = String.format("%s mkvob -tag %s -c \"%s\" -stgloc -auto", getCleartoolExecutable(), tag, reason);
    final String[] execAndWait = getCommandExecutor().execAndWait(command);
    LOG.debug(String.format("The Vob created: %s", Arrays.toString(execAndWait)));
    return new VobObjectParser(execAndWait);
  }

  private static boolean isVobExists(String tag) {
    try {
      getCommandExecutor().execAndWait(String.format("%s lsvob \\%s", getCleartoolExecutable(), tag));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static void dropVob(String globalPath) throws IOException, InterruptedException {
    getCommandExecutor().execAndWait(String.format(CMD_RMVOB, getCleartoolExecutable(), globalPath));
    LOG.debug(String.format("The Vob \"%s\" has been dropt", globalPath));
  }

  static void dropView(String globalPath) throws IOException, InterruptedException {
    getCommandExecutor().execAndWait(String.format(CMD_RMVIEW, getCleartoolExecutable(), globalPath));
    LOG.debug(String.format("The View \"%s\" has been dropt", globalPath));
  }

  static VobObjectParser createSnapshotView(@NotNull String tag, @Nullable String stream, @Nullable String globalViewPath, @NotNull String localViewPath, @Nullable String reason) throws IOException, InterruptedException {
    //TODO: why here ????
    final File parentFile = new File(localViewPath).getParentFile();
    if (parentFile != null && !parentFile.exists()) {
      parentFile.mkdirs();
    }
    //fire
    final String command;
    if (globalViewPath != null) {
      if (stream == null) {
        command = String.format(CMD_MKVIEW_VWS, getCleartoolExecutable(), tag, globalViewPath, reason, localViewPath);
      } else {
        command = String.format(CMD_MKVIEW_VWS_UCM, getCleartoolExecutable(), tag, stream, globalViewPath, reason, localViewPath);
      }
    } else {
      if (stream == null) {
        command = String.format(CMD_MKVIEW_AUTOLOC, getCleartoolExecutable(), tag, reason, localViewPath);
      } else {
        command = String.format(CMD_MKVIEW_AUTOLOC_UCM, getCleartoolExecutable(), tag, stream, reason, localViewPath);
      }
    }
    final String[] execAndWait = getCommandExecutor().execAndWait(command);
    LOG.debug(String.format("View created: %s", Arrays.toString(execAndWait)));
    return new VobObjectParser(execAndWait);
  }

  static ChangeParser[] update(final File path, final Date to) throws IOException, InterruptedException {
    final File file = Util.createTempFile();
    try {
      final String command = String.format(CMD_UPDATE, getCleartoolExecutable(), file.getAbsolutePath());
      getCommandExecutor().execAndWait(command, path);
      return parseUpdateOut(new FileInputStream(file));
    } finally {
      file.delete();
    }
  }

  static ChangeParser[] lsChange(File path) throws IOException, InterruptedException {
    final File file = Util.createTempFile();
    try {
      final String command = String.format(CMD_LSCHANGE, getCleartoolExecutable(), file.getAbsolutePath());
      getCommandExecutor().execAndWait(command, path);
      return parseUpdateOut(new FileInputStream(file));
    } finally {
      file.delete();
    }

  }

  static HistoryParser[] lsHistory(File file, boolean isDirectory, boolean useAll) throws IOException, InterruptedException {
    final String command;
    if (isDirectory) {
      command = String.format(useAll ? CMD_LSHISTORY_CONTAINER_ALL : CMD_LSHISTORY_CONTAINER, getCleartoolExecutable(), HistoryParser.OUTPUT_FORMAT_WITHOUT_COMMENTS, file.getAbsolutePath());
    } else {
      command = String.format(CMD_LSHISTORY_CONTAINING, getCleartoolExecutable(), HistoryParser.OUTPUT_FORMAT_WITHOUT_COMMENTS, file.getAbsolutePath());
    }
    final ArrayList<HistoryParser> buffer = new ArrayList<HistoryParser>();
    final String[] output = getCommandExecutor().execAndWait(command);
    for (String line : output) {
      buffer.add(new HistoryParser(line));
    }
    return buffer.toArray(new HistoryParser[buffer.size()]);

  }

  private static ChangeParser[] parseUpdateOut(InputStream stream) throws IOException {
    final BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
    try {
      final ArrayList<ChangeParser> out = new ArrayList<ChangeParser>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (ChangeParser.accept(line)) {
          out.add(new ChangeParser(line));
        }
      }
      return out.toArray(new ChangeParser[out.size()]);
    } finally {
      reader.close();
    }
  }

  static String[] getFullEnvp(String[] extraEnvp) {
    final ArrayList<String> out = new ArrayList<String>();
    for (Map.Entry<String, String> sysEnvp : System.getenv().entrySet()) {
      out.add(String.format("%s=%s", sysEnvp.getKey(), sysEnvp.getValue()));
    }
    Collections.addAll(out, extraEnvp);
    return out.toArray(new String[out.size()]);
  }

  static VobParser[] lsVob() throws IOException, InterruptedException {
    final String command = String.format(CMD_LSVOB, getCleartoolExecutable());
    final String[] stdOut = getCommandExecutor().execAndWait(command);
    final ArrayList<String> buffer = new ArrayList<String>();
    final ArrayList<VobParser> out = new ArrayList<VobParser>();
    for (String line : stdOut) {
      final String trim = line.trim();
      if (trim.length() > 0) {
        if (trim.startsWith(VobParser.TAG_TOKEN) && !buffer.isEmpty()) {
          // reach the next section
          out.add(new VobParser(buffer.toArray(new String[buffer.size()])));
          buffer.clear();
        }
        buffer.add(trim);
      }
    }
    if (!buffer.isEmpty()) {
      out.add(new VobParser(buffer.toArray(new String[buffer.size()])));// do_not_forget_the_last
    }
    return out.toArray(new VobParser[out.size()]);
  }

  static StorageParser[] lsStgLoc() throws IOException, InterruptedException {
    final String command = String.format(CMD_LSLOCATIONS, getCleartoolExecutable());
    final String[] stdOut = getCommandExecutor().execAndWait(command);
    final ArrayList<String> buffer = new ArrayList<String>();
    final ArrayList<StorageParser> out = new ArrayList<StorageParser>();
    for (String line : stdOut) {
      final String trim = line.trim();
      if (trim.startsWith(StorageParser.NAME_TOKEN) && !buffer.isEmpty()) {
        // next_section_reached
        out.add(new StorageParser(buffer.toArray(new String[buffer.size()])));
        buffer.clear();
      }
      buffer.add(trim);
    }
    // do_not_forget_the_last
    out.add(new StorageParser(buffer.toArray(new String[buffer.size()])));// do
    return out.toArray(new StorageParser[out.size()]);
  }

  static ViewParser[] lsView() throws IOException, InterruptedException {
    final String command = String.format(CMD_LSVIEW, getCleartoolExecutable());
    final String[] stdOut = getCommandExecutor().execAndWait(command);
    final ArrayList<String> buffer = new ArrayList<String>();
    final ArrayList<ViewParser> out = new ArrayList<ViewParser>();
    for (String line : stdOut) {
      final String trim = line.trim();
      if (trim.length() > 0) {
        if (trim.startsWith(ViewParser.TAG_TOKEN) && !buffer.isEmpty()) {
          // reach the next section
          out.add(new ViewParser(buffer.toArray(new String[buffer.size()])));
          buffer.clear();
        }
        buffer.add(trim);
      }
    }
    if (!buffer.isEmpty()) {
      // do_not_forget_the_last
      out.add(new ViewParser(buffer.toArray(new String[buffer.size()])));
    }
    return out.toArray(new ViewParser[out.size()]);
  }

  static ViewParser lsView(File root) throws IOException, InterruptedException {
    final String command = String.format(CMD_DSCRVIEW_IN_FOLDER, getCleartoolExecutable());
    return new ViewParser(getCommandExecutor().execAndWait(command, root));
  }

  static ViewParser lsView(String viewTag) throws IOException, InterruptedException {
    final String command = String.format(CMD_DSCRVIEW_BY_TAG, getCleartoolExecutable(), viewTag);
    return new ViewParser(getCommandExecutor().execAndWait(command));
  }

  /**
   * Label's info is not provided
   */
  public static String[] lsVTree(File root) throws IOException, InterruptedException {
    final File executionFolder = getFolder(root);
    final String relativePath = FileUtil.getRelativePath(executionFolder, root);
    final String command = String.format(CMD_LSVTREE, getCleartoolExecutable(), relativePath);
    final String[] rawOut = getCommandExecutor().execAndWait(command, executionFolder);
    // remove labels info
    final ArrayList<String> out = new ArrayList<String>(rawOut.length);
    for (String line : rawOut) {
      int bracketIndex = line.indexOf("(");
      if (bracketIndex != -1) {
        out.add(line.substring(0, bracketIndex - 1).trim());
      } else {
        out.add(line.trim());
      }
    }
    return out.toArray(new String[out.size()]);
  }

  public static VersionParser describe(File root, String version) throws IOException, InterruptedException {
    final String command = MessageFormat.format(CMD_DESCRIBE, getCleartoolExecutable(), version);
    return new VersionParser(getCommandExecutor().execAndWait(command, getFolder(root)));
  }

  private static File getFolder(File file) {
    return file.isDirectory() ? file : file.getParentFile();
  }

  interface ICCOutputParser {
    String[] getStdout();
  }

  public static class VersionParser extends AbstractCCParser {

    private String myVersion;

    private Date myCreationDate;

    private String myPath;

    private String[] myLabels = new String[0];

    protected VersionParser(String[] stdout) {
      super(stdout);
      for (String line : stdout) {
        final String[] elements = line.trim().split(";");
        int sepIndex = elements[1].indexOf("@@");
        myPath = elements[1].substring(0, sepIndex);
        myVersion = elements[1].substring(sepIndex + 2);
        try {
          myCreationDate = new SimpleDateFormat("yyyyMMdd.hhmmss").parse(elements[2]);

        } catch (ParseException e) {
          new RuntimeException(e);
        }
        // labels
        if (elements.length > 3 && elements[3].trim().length() > 0) {
          String labels = elements[3].trim();
          labels = labels.substring(1, labels.length() - 1);// extract brackets
          myLabels = labels.split(", ");
        }
      }
    }

    public String getPath() {
      return myPath;
    }

    public String getVersion() {
      return myVersion;
    }

    public Date getCreationDate() {
      return myCreationDate;
    }

    public String[] getLabels() {
      return myLabels;
    }

    @Override
    public String toString() {
      return String.format("{VersionParser: path=%s, version=%s, created=%s, labels=%s}", getPath(), getVersion(), getCreationDate(), Arrays.toString(getLabels()));
    }

  }

  static class VobObjectParser implements ICCOutputParser {

    private static final Pattern HOST_LOCAL_PATH_PATTERN = Pattern.compile("Host-local path: (.*)");
    private static final Pattern GLOBAL_PATH_PATTERN = Pattern.compile("Global path: (.*)");

    private String myHostLocalPath;
    private String myGlobalPath;
    private final String[] myStdOut;

    VobObjectParser(String[] stdout) {
      myStdOut = stdout;
      for (String line : myStdOut) {
        line = line.trim();
        Matcher matcher = HOST_LOCAL_PATH_PATTERN.matcher(line);
        if (matcher.matches()) {
          myHostLocalPath = matcher.group(1).trim();
          continue;
        }
        matcher = GLOBAL_PATH_PATTERN.matcher(line);
        if (matcher.matches()) {
          myGlobalPath = matcher.group(1).trim();
          if (!(myGlobalPath.endsWith(".vws") || myGlobalPath.endsWith(".vbs"))) {
            throw new RuntimeException(String.format("Wrong Global Path parsing: %s", myGlobalPath));
          }
        }
      }
    }

    public String getHostLocalPath() {
      return myHostLocalPath;
    }

    public String getGlobalPath() {
      return myGlobalPath;
    }

    public String[] getStdout() {
      return myStdOut;
    }

  }

  static abstract class AbstractCCParser implements ICCOutputParser {

    private final String[] myStdOut;

    protected AbstractCCParser(String[] stdout) {
      myStdOut = stdout;
    }

    public String[] getStdout() {
      return myStdOut;
    }

    protected String getRest(String trim, String tagToken) {
      return trim.substring(tagToken.length()).trim();
    }

  }

  static class HistoryParser {

    private static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("yyyyMMddHHmmss");

    //    static final String OUTPUT_FORMAT = "%Nd#--#%En#--#%m#--#%Vn#--#%o#--#%e#--#%Nc#--#%[activity]p\\n";
    //    static final String OUTPUT_FORMAT =                "%Nd#--#%En#--#%m#--#%Vn#--#%o#--#%e#--#%Nc#--#%[activity]p\\n";    
    static final String OUTPUT_FORMAT_WITHOUT_COMMENTS = "%Nd#--#%En#--#%m#--#%Vn#--#%o#--#%e#--#%[activity]p\\n";
    //private static Pattern PATTERN = Pattern.compile("(\\d\\d\\d\\d)(\\d\\d)(\\d\\d)\\.(\\d\\d)(\\d\\d)(\\d\\d)#--#(.*)#--#(.*)#--#(.*)#--#(.*)#--#(.*)#--#(.*)#--#(.*)");
    private static final Pattern PATTERN_WITHOUT_COMMENTS = Pattern.compile("(\\d*)\\.(\\d*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)");
    //private static Pattern PATTERN = Pattern.compile("(\\d*)\\.(\\d*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)");
    //    private static Pattern PATTERN = Pattern.compile("(\\d*)\\.(\\d*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)#--#(.*?)");    
    //    private static Pattern ACTIVITY_PATTERN = Pattern.compile("(.*)#--#(.*)");

    final boolean isValid;
    final Date date;
    final String path;
    final String kind;
    final String version;
    final String operation;
    final String event;
    final String comment = null;
    final String activity;

    HistoryParser(String line) throws IOException {
      line = line.trim();
      Matcher matcher = PATTERN_WITHOUT_COMMENTS.matcher(line);
      if (matcher.matches()) {
        try {
          isValid = true;
          // date
          final String dateStr = new StringBuilder(matcher.group(1)).append(matcher.group(2)).toString();
          date = DATE_FORMATTER.parse(dateStr);
          // relative path(object)
          path = matcher.group(3);
          // kind
          kind = matcher.group(4);
          // version
          version = matcher.group(5);
          // operation
          operation = matcher.group(6);
          // event
          event = matcher.group(7);
          //          //comment          
          //          comment = matcher.group(8);
          //activity
          final String activityStr = matcher.group(8/*9*/);
          activity = activityStr.length() > 0 ? activityStr : null;

          //          // event
          //          event = matcher.group(7);
          //          // parse last group potentially contains activity
          //          final String lastGroup = matcher.group(8);
          //          final Matcher activityMatcher = ACTIVITY_PATTERN.matcher(lastGroup);
          //          if (activityMatcher.matches()) {
          //            comment = activityMatcher.group(1);
          //            final String activityGroup = activityMatcher.group(2).trim();
          //            activity = activityGroup.length() > 0 ? activityGroup : null;
          //          } else {
          //            comment = lastGroup;
          //            activity = null;
          //          }
          return;

        } catch (Exception e) {
          throw new IOException(e.getMessage());
        }
      }

      throw new IOException(String.format("The \"%s\" output line is not matched", line));
    }

  }

  static class ChangeParser {

    static final String UPDATED_TOKEN = "Updated:";
    static final String NEW_TOKEN = "New:";
    static final String UNLOADED_DELETED_TOKEN = "UnloadDeleted:";

    private boolean isDeletion;

    private boolean isChange;

    private boolean isAddition;

    private final String myLocalPath;

    private String myVersionBefor;

    private String myVersionAfter;

    static boolean accept(String line) {
      line = line.trim();
      return line.startsWith(UPDATED_TOKEN) || line.startsWith(NEW_TOKEN) || line.startsWith(UNLOADED_DELETED_TOKEN);
    }

    protected ChangeParser(String line) {
      int tokenLength = 0;
      // detect change kind
      if (line.startsWith(UPDATED_TOKEN)) {
        tokenLength = UPDATED_TOKEN.length();
        isChange = true;
      } else if (line.startsWith(NEW_TOKEN)) {
        tokenLength = NEW_TOKEN.length();
        isAddition = true;
      } else if (line.startsWith(UNLOADED_DELETED_TOKEN)) {
        tokenLength = UNLOADED_DELETED_TOKEN.length();
        isDeletion = true;
      }
      // extract token
      line = line.substring(tokenLength).trim();
      // get local path
      int versionBeforStartIdx = 0;
      if (line.startsWith("\"")) {
        versionBeforStartIdx = line.indexOf("\"", 1);
        myLocalPath = line.substring(1, versionBeforStartIdx);
      } else {
        versionBeforStartIdx = line.indexOf(" ");
        if (versionBeforStartIdx != -1) {
          myLocalPath = line.substring(0, versionBeforStartIdx);
        } else {
          myLocalPath = line.trim();
        }

      }
      // discover version part if exists
      if (versionBeforStartIdx != -1) {
        String versionsPart = line.substring(versionBeforStartIdx + 1).trim();
        final String[] versions = versionsPart.split("[ +]");
        if (versions.length > 0) {
          myVersionAfter = versions[0];
        }
        if (versions.length > 1) {
          myVersionBefor = myVersionAfter;
          myVersionAfter = versions[1];
        }
      }
    }

    public boolean isDeletion() {
      return isDeletion;
    }

    public boolean isChange() {
      return isChange;
    }

    public boolean isAddition() {
      return isAddition;
    }

    public String getLocalPath() {
      return myLocalPath;
    }

    public String getRevisionBefor() {
      return myVersionBefor;
    }

    public String getRevisionAfter() {
      return myVersionAfter;
    }

    @Override
    public String toString() {
      return String.format("{ChangeParser: added=%s, changed=%s, deleted=%s, path=\"%s\", befor=%s, after=%s}", isAddition(), isChange(), isDeletion(), getLocalPath(), getRevisionBefor(), getRevisionAfter());
    }

  }

  static class StorageParser extends AbstractCCParser {

    static final String NAME_TOKEN = "Name: ";
    static final String TYPE_TOKEN = "Type: ";
    static final String GLOBAL_PATH_TOKEN = "Global path: ";
    static final String SERVER_HOST_TOKEN = "Server host: ";
    private String myType;
    private String myGlobalPath;
    private String myServerHost;
    private String myTag;

    protected StorageParser(String[] stdout) {
      super(stdout);
      for (String line : stdout) {
        final String trim = line.trim();
        if (trim.startsWith(NAME_TOKEN)) {
          myTag = getRest(trim, NAME_TOKEN);
        } else if (trim.startsWith(TYPE_TOKEN)) {
          myType = getRest(trim, TYPE_TOKEN);
        } else if (trim.startsWith(GLOBAL_PATH_TOKEN)) {
          myGlobalPath = getRest(trim, GLOBAL_PATH_TOKEN);
        } else if (trim.startsWith(SERVER_HOST_TOKEN)) {
          myServerHost = getRest(trim, SERVER_HOST_TOKEN);
        }
      }

    }

    public String getTag() {
      return myTag;
    }

    public String getType() {
      return myType;
    }

    public String getGlobalPath() {
      return myGlobalPath;
    }

    public String getServerHost() {
      return myServerHost;
    }

  }

  static class VobParser extends AbstractCCParser {

    static final String TAG_TOKEN = "Tag: ";
    static final String GLOBAL_PATH_TOKEN = "Global path: ";
    static final String SERVER_HOST_TOKEN = "Server host: ";
    static final String ACCESS_TOKEN = "Access: ";
    static final String MOUNT_OPTIONS_TOKEN = "Mount options: ";
    static final String REGION_TOKEN = "Region: ";
    static final String ACTIVE_TOKEN = "Active: ";
    static final String VOB_TAG_REPLICA_TOKEN = "Vob tag replica uuid: ";
    static final String HOST_TOKEN = "Vob on host: ";
    static final String VOB_SERVER_ACCESS_TOKEN = "Vob server access path: ";
    static final String VOB_FAMILY_REPLICA_TOKEN = "Vob family uuid:  ";
    static final String VOB_REPLICA_REPLICA_TOKEN = "Vob replica uuid: ";
    static final String VOB_REGISTRY_ATTRIBUTE_TOKEN = "Vob registry attributes";

    private String myTag;
    private String myGlobalPath;
    private String myServerHost;
    private String myRegion;
    private String myServerAccessPath;

    VobParser(String[] stdout) {
      super(stdout);
      for (String line : stdout) {
        final String trim = line.trim();
        if (trim.startsWith(TAG_TOKEN)) {
          final String[] split = trim.split(" +");
          myTag = getRest(String.format("%s %s", split[0], split[1]), TAG_TOKEN);// as far as cleartool put the comment next to the tag
        } else if (trim.startsWith(GLOBAL_PATH_TOKEN)) {
          myGlobalPath = getRest(trim, GLOBAL_PATH_TOKEN);
        } else if (trim.startsWith(SERVER_HOST_TOKEN)) {
          myServerHost = getRest(trim, SERVER_HOST_TOKEN);
        } else if (trim.startsWith(REGION_TOKEN)) {
          myRegion = getRest(trim, REGION_TOKEN);
        } else if (trim.startsWith(VOB_SERVER_ACCESS_TOKEN)) {
          myServerAccessPath = getRest(trim, VOB_SERVER_ACCESS_TOKEN);
        }
      }
    }

    public String getTag() {
      return myTag;
    }

    public String getGlobalPath() {
      return myGlobalPath;
    }

    public String getServerHost() {
      return myServerHost;
    }

    public String getRegion() {
      return myRegion;
    }

    public String getServerAccessPath() {
      return myServerAccessPath;
    }

    @Override
    public String toString() {
      return String.format("region=%s, tag=%s, serverHost=%s, globalPath=%s, serverAccessPath=%s", getRegion(), getTag(), getServerHost(), getGlobalPath(), getServerAccessPath());
    }

  }

  static class ViewParser extends VobParser {

    static final String ATTRIBUTE_SNAPSHOT = "snapshot";
    static final String ATTRIBUTE_UCM = "ucmview";

    static final Pattern VIEW_VIEW_TAG_UID_PATTERN = Pattern.compile("View tag uuid: (.*)");
    static final Pattern VIEW_UID_PATTERN = Pattern.compile("View uuid: (.*)");
    static final Pattern VIEW_OWNER_PATTERN = Pattern.compile("View owner: (.*)");
    static final Pattern VIEW_ATTRIBUTES_PATTERN = Pattern.compile("View attributes: (.*)");

    private String myUUID;
    private String myAttributes = "";
    private String myOwner;

    protected ViewParser(String[] stdout) {
      super(stdout);
      for (String line : stdout) {
        final Matcher uidMatcher = VIEW_UID_PATTERN.matcher(line.trim());
        if (uidMatcher.matches()) {
          myUUID = uidMatcher.group(1);
          continue;
        }
        final Matcher viewOwnerMatcher = VIEW_OWNER_PATTERN.matcher(line.trim());
        if (viewOwnerMatcher.matches()) {
          myOwner = viewOwnerMatcher.group(1);
          continue;
        }
        final Matcher attributeMatcher = VIEW_ATTRIBUTES_PATTERN.matcher(line.trim());
        if (attributeMatcher.matches()) {
          myAttributes = attributeMatcher.group(1);
        }
      }
    }

    public String getUUID() {
      return myUUID;
    }

    public String getAttributes() {
      return myAttributes;
    }

    public String getOwner() {
      return myOwner;
    }
    
    @Override
    public String toString() {
      return String.format("%s, uuid=%s, attributes=%s", super.toString(), getUUID(), getAttributes());
    }
    

  }

  static class StreamParser extends AbstractCCParser {
    static final Pattern STREAM_NAME_PATTERN_WIN = Pattern.compile("stream \"(.*)\"");
    static final Pattern STREAM_NAME_PATTERN_NIX = Pattern.compile("stream '(.*)'");
    static final Pattern STREAM_PROJECT_PATTERN = Pattern.compile("project: (.*)");

    private String myName;

    private String myProject;

    protected StreamParser(String[] stdout) {
      super(stdout);
      for (String line : stdout) {
        final Matcher winNameMatcher = STREAM_NAME_PATTERN_WIN.matcher(line.trim());
        if (winNameMatcher.matches()) {
          myName = winNameMatcher.group(1);
          continue;
        }
        final Matcher nixNameMatcher = STREAM_NAME_PATTERN_NIX.matcher(line.trim());
        if (nixNameMatcher.matches()) {
          myName = nixNameMatcher.group(1);
          continue;
        }
        final Matcher projectMatcher = STREAM_PROJECT_PATTERN.matcher(line.trim());
        if (projectMatcher.matches()) {
          myProject = projectMatcher.group(1).split(" ")[0];//annotation can be here
        }
      }
    }

    public String getName() {
      return myName;
    }

    public String getProject() {
      return myProject;
    }

    public String getProjectSelector() {
      String project = getProject();
      final int winSlash = getProject().lastIndexOf("\\");
      final int nixSlash = getProject().lastIndexOf("/");
      final int slashIndex = Math.max(winSlash, nixSlash);
      if (slashIndex > -1) {
        return project.substring(slashIndex + 1);
      }
      return project;
    }

  }

  static StreamParser lsStream(final @NotNull String viewTag) throws IOException, InterruptedException {
    final String command = String.format(CMD_LSSTREAM, getCleartoolExecutable(), viewTag);
    return new StreamParser(getCommandExecutor().execAndWait(command));
  }

  static ChangeParser[] setConfigSpecs(File myLocalPath, ArrayList<String> configSpecs) throws IOException, InterruptedException {
    final File cffile = FileUtil.createTempFile("config_spec_", "");
    try {
      final FileWriter writer = new FileWriter(cffile);
      try {
        for (String spec : configSpecs) {
          writer.write(spec);
          writer.write("\n");
        }
        writer.flush();
      } finally {
        FileUtil.close(writer);
      }
      /**
       * set NOTE: must be executed under root hierarchy of Snapshot View
       */
      // "-overwrite" does not support by 2003 final String command =
      // String.format("cleartool setcs -overwrite \"%s\"",
      // cffile.getAbsolutePath());
      final String command = String.format(CMD_SETCS, getCleartoolExecutable(), cffile.getAbsolutePath());
      try {
        getCommandExecutor().execAndWait(command, "yes", myLocalPath);
        return new ChangeParser[0];// should not reach there

      } catch (Exception e) {
        // as far setcs writes log file name to stderr have to catch the issue
        final String message = e.getMessage().trim();
        final int pos = message.indexOf("Log has been written to");
        if (pos != -1) {
          int firstQuotaIdx = message.indexOf("\"", pos);
          int secondQuotaIdx = message.indexOf("\"", firstQuotaIdx + 1);
          final String absolutePath = message.substring(firstQuotaIdx + 1, secondQuotaIdx);
          return parseUpdateOut(new FileInputStream(absolutePath));
        }
        else {
          throw new IOException(e.getMessage());
        }
      }

    } finally {
      cffile.delete();
    }

  }

  static List<String> getConfigSpecs(final String viewTag) throws IOException, InterruptedException {
    final String command = String.format(CMD_CATCS, getCleartoolExecutable(), viewTag);
    final String[] result = getCommandExecutor().execAndWait(command);
    return Arrays.asList(result);
  }

  static boolean isCheckedout(File root, File file) throws IOException, InterruptedException {
    final String cmd;
    if (file.isDirectory()) {
      cmd = "%s lscheckout -long -directory %s";
    } else {
      cmd = "%s lscheckout -long %s";
    }
    final String[] response = getCommandExecutor().execAndWait(String.format(cmd, getCleartoolExecutable(), file.getAbsolutePath()), root);
    return !(response.length == 0 || (response.length == 1 && response[0].trim().length() == 0));
  }

  static void checkout(File root, File file, String reason) throws IOException, InterruptedException {
    getCommandExecutor().execAndWait(String.format(CMD_CHECKOUT, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void mkelem(File root, File file, String reason) throws IOException, InterruptedException {
    getCommandExecutor().execAndWait(String.format(CMD_MKELEM, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void checkin(File root, File file, String reason) throws IOException, InterruptedException {
    getCommandExecutor().execAndWait(String.format(CMD_CHECKIN, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void rmname(File root, File file, String reason) throws IOException, InterruptedException {
    getCommandExecutor().execAndWait(String.format(CMD_RMNAME, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void rmelem(File root, File[] files, String reason) throws IOException, InterruptedException {
    if (files != null && files.length > 0) {
      final StringBuilder names = new StringBuilder();
      for (File file : files) {
        names.append("\"").append(file.getName()).append("\"").append(" ");
      }
      String command = String.format(CMD_RMELEM, getCleartoolExecutable(), reason, names.toString());
      getCommandExecutor().execAndWait(command, root);
    }
  }

  static void rmver(File root, File file, String version, String reason) throws IOException, InterruptedException {
    getCommandExecutor().execAndWait(String.format(CMD_RMVER, getCleartoolExecutable(), version, reason, file.getAbsolutePath()), root);
  }

}
