/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jetbrains.buildServer.util.FileUtil;

import org.apache.log4j.Logger;

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
  private static final String CMD_LSCHANGE = Messages.getString("CTool.cmd_lschanges"); //$NON-NLS-1$
  private static final String CMD_UPDATE = Messages.getString("CTool.cmd_update"); //$NON-NLS-1$
  private static final String CMD_MKVIEW = Messages.getString("CTool.cmd_mkview"); //$NON-NLS-1$
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

  private static final Logger LOG = Logger.getLogger(CTool.class);

  private static String ourSessionUser;
  private static String ourSessionPassword;
  private static String ourCleartoolExecutable = "cleartool"; //$NON-NLS-1$

  static final List<String> DEFAULT_CONFIG_SPECS = Collections.unmodifiableList(new ArrayList<String>() {
    private static final long serialVersionUID = 1L;
    {
      add("element * CHECKEDOUT"); //$NON-NLS-1$
      add("element * /main/LATEST"); //$NON-NLS-1$
    }
  });

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
    final String[] execAndWait = Util.execAndWait(command);
    LOG.debug(String.format("The Vob created: %s", Arrays.toString(execAndWait)));
    return new VobObjectParser(execAndWait);
  }

  private static boolean isVobExists(String tag) {
    try {
      Util.execAndWait(String.format("%s lsvob \\%s", getCleartoolExecutable(), tag));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static VobObjectParser importVob(final String tag, final File dump, final String reason) throws IOException, InterruptedException, CCException {
    final long timeStamp = System.currentTimeMillis();
    CCStorage anyStorage = null;
    File silentCmd = null;
    try {
      // looking for any VOB for storage detection
      CCStorage[] availableVobs = new CCRegion("any").getStorages();
      if (availableVobs.length == 0) {
        throw new CCException("No one VOB Storage found");
      }
      anyStorage = availableVobs[0];
      final String uploadCommand = String.format("xcopy /Y \"%s\" %s", dump, anyStorage.getGlobalPath());// TODO:
      // use
      // timestamp
      // in
      // file
      // name

      /**
       * upload replica to target host. it required by replica's import
       */
      Util.execAndWait(uploadCommand);
      /**
       * run command using psexec make sure the user account has administrative
       * privileges on target host
       */

      silentCmd = new File(String.format("import_replica_%s.cmd", timeStamp));
      silentCmd.createNewFile();
      final FileWriter writer = new FileWriter(silentCmd);
      writer.write("@echo off\n");
      writer.write("echo yes>yes.txt\n");
      writer.write(String.format("multitool mkreplica -import -workdir %s -c \"%s\" -tag \\%s -stgloc -auto -npreserve %s 2>&1 1>>c:\\replica.log 0<yes.txt\n", "c:\\rep.tmp", reason, tag, String.format("%s\\%s", anyStorage.getGlobalPath(), dump.getName())));// TODO:
      // use
      // timestamp
      // in
      // file
      // name
      writer.write("del /F /Q yes.txt\n");
      writer.close();

      final String command;

      if (ourSessionUser != null) {
        // the tool was logged in
        command = String.format("psexec \\\\%s -u %s -p %s -c %s", anyStorage.getServerHost(), ourSessionUser, ourSessionPassword, silentCmd.getAbsolutePath());
      } else {
        // use current credentials
        command = String.format("psexec \\\\%s -c %s", anyStorage.getServerHost(), silentCmd.getAbsolutePath());
      }
      try {
        Util.execAndWait(command);
      } catch (Exception e) {
        // psexec writes own output to stderr
      }
      // read VOB properties
      final VobObjectParser vobObjectResult = new VobObjectParser(Util.execAndWait(String.format("%s lsvob -long \\%s", getCleartoolExecutable(), tag)));
      if (vobObjectResult.getGlobalPath() == null) {
        throw new IOException(String.format("Could not create %s VOB", tag));
      }
      return vobObjectResult;

    } finally {
      // cleanup
      if (silentCmd != null) {
        silentCmd.delete();
      }
      if (anyStorage != null) {
        Util.execAndWait(String.format("cmd /c del /F /Q \"%s\\%s\"", anyStorage.getGlobalPath(), dump.getName()));
      }
    }

  }

  static void dropVob(String globalPath) throws IOException, InterruptedException {
    Util.execAndWait(String.format(CMD_RMVOB, getCleartoolExecutable(), globalPath));
    LOG.debug(String.format("The Vob \"%s\" has been dropt", globalPath));
  }

  static void dropView(String globalPath) throws IOException, InterruptedException {
    Util.execAndWait(String.format(CMD_RMVIEW, getCleartoolExecutable(), globalPath));
    LOG.debug(String.format("The View \"%s\" has been dropt", globalPath));
  }

  static VobObjectParser createSnapshotView(String tag, File path, String reason) throws IOException, InterruptedException {
    path.getParentFile().mkdirs();
    final String command = String.format(CMD_MKVIEW, getCleartoolExecutable(), tag, reason, path.getAbsolutePath());
    final String[] execAndWait = Util.execAndWait(command);
    LOG.debug(String.format("View created: %s", Arrays.toString(execAndWait)));
    return new VobObjectParser(execAndWait);
  }

  static ChangeParser[] update(final File path, final Date to) throws IOException, InterruptedException {
    final File file = Util.createTempFile();
    try {
      final String command = String.format(CMD_UPDATE, getCleartoolExecutable(), file.getAbsolutePath());
      Util.execAndWait(command, path);
      return parseUpdateOut(new FileInputStream(file));
    } finally {
      file.delete();
    }
  }

  static ChangeParser[] lsChange(File path) throws IOException, InterruptedException {
    final File file = Util.createTempFile();
    try {
      final String command = String.format(CMD_LSCHANGE, getCleartoolExecutable(), file.getAbsolutePath());
      Util.execAndWait(command, path);
      return parseUpdateOut(new FileInputStream(file));
    } finally {
      file.delete();
    }

  }

  static HistoryParser[] lsHistory(File file, boolean isDirectory) throws IOException, InterruptedException {
    final String command;
    if (isDirectory) {
      command = String.format(CMD_LSHISTORY_CONTAINER, getCleartoolExecutable(), HistoryParser.OUTPUT_FORMAT, file.getAbsolutePath());
    } else {
      command = String.format(CMD_LSHISTORY_CONTAINING, getCleartoolExecutable(), HistoryParser.OUTPUT_FORMAT, file.getAbsolutePath());
    }
    final ArrayList<HistoryParser> buffer = new ArrayList<HistoryParser>();
    final String[] output = Util.execAndWait(command);
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
    for (String extraEnv : extraEnvp) {
      out.add(extraEnv);
    }
    return out.toArray(new String[out.size()]);
  }

  static VobParser[] lsVob() throws IOException, InterruptedException {
    final String command = String.format(CMD_LSVOB, getCleartoolExecutable());
    final String[] stdOut = Util.execAndWait(command);
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
    final String[] stdOut = Util.execAndWait(command);
    final ArrayList<String> buffer = new ArrayList<String>();
    final ArrayList<StorageParser> out = new ArrayList<StorageParser>();
    for (String line : stdOut) {
      final String trim = line.trim();
      if (trim.startsWith(StorageParser.NAME_TOKEN) && !buffer.isEmpty()) {
        // reach the next section
        out.add(new StorageParser(buffer.toArray(new String[buffer.size()])));
        buffer.clear();
      }
      buffer.add(trim);
    }
    out.add(new StorageParser(buffer.toArray(new String[buffer.size()])));// do
    // not
    // forget
    // the
    // last
    return out.toArray(new StorageParser[out.size()]);
  }

  static ViewParser[] lsView() throws IOException, InterruptedException {
    final String command = String.format(CMD_LSVIEW, getCleartoolExecutable());
    final String[] stdOut = Util.execAndWait(command);
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
      out.add(new ViewParser(buffer.toArray(new String[buffer.size()])));// do
      // not
    }
    return out.toArray(new ViewParser[out.size()]);
  }

  static ViewParser lsView(File root) throws IOException, InterruptedException {
    final String command = String.format(CMD_DSCRVIEW_IN_FOLDER, getCleartoolExecutable());
    return new ViewParser(Util.execAndWait(command, root));
  }

  static ViewParser lsView(String viewTag) throws IOException, InterruptedException {
    final String command = String.format(CMD_DSCRVIEW_BY_TAG, getCleartoolExecutable(), viewTag);
    return new ViewParser(Util.execAndWait(command));
  }

  /**
   * Label's info is not provided
   */
  public static String[] lsVTree(File root) throws IOException, InterruptedException {
    final File executionFolder = getFolder(root);
    final String relativePath = FileUtil.getRelativePath(executionFolder, root);
    final String command = String.format(CMD_LSVTREE, getCleartoolExecutable(), relativePath);
    final String[] rawOut = Util.execAndWait(command, executionFolder);
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
    return new VersionParser(Util.execAndWait(command, getFolder(root)));
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
        myVersion = elements[1].substring(sepIndex + 2, elements[1].length());
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
    private String[] myStdOut;

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
          continue;
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

    private String[] myStdOut;

    protected AbstractCCParser(String[] stdout) {
      myStdOut = stdout;
    }

    public String[] getStdout() {
      return myStdOut;
    }

    protected String getRest(String trim, String tagToken) {
      return trim.substring(tagToken.length(), trim.length()).trim();
    }

  }

  static class HistoryParser {

    private static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("yyyyMMddHHmmss");
    static final String OUTPUT_FORMAT = "%Nd#--#%En#--#%m#--#%Vn#--#%o#--#%e#--#%Nc#--#%[activity]p\\n";
    private static Pattern PATTERN = Pattern.compile("(\\d\\d\\d\\d)(\\d\\d)(\\d\\d)\\.(\\d\\d)(\\d\\d)(\\d\\d)#--#(.*)#--#(.*)#--#(.*)#--#(.*)#--#(.*)#--#(.*)#--#(.*)");

    // cleartool lshistory -fmt
    // "%Nd#--#%En#--#%m#--#%Vn#--#%o#--#%e#--#%Nc#--#%[activity]p\n" .
    // 20101019.134210#--#f0eb67e7c0fa9bcb7d73215e64d51721#--#version#--#\main\1#--#checkin#--#create
    // version#--#Time delta meter#--#
    // cleartool lshistory -directory -fmt
    // "%Nd#--#%En#--#%m#--#%Vn#--#%o#--#%e#--#%Nc#--#%[activity]p\n" .
    // 20101019.134211#--#.#--#directory version#--#\main\9#--#checkin#--#create
    // directory version#--#Time delta meter#--#
    private boolean isValid;
    final Date date;
    final String path;
    final String kind;
    final String version;
    final String operation;
    final String event;
    final String comment;
    final String activity;

    HistoryParser(String line) throws IOException {
      final Matcher matcher = PATTERN.matcher(line.trim());
      if (matcher.matches()) {
        try {
          // date
          final String dateStr = String.format("%s%s%s%s%s%s", matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), matcher.group(5), matcher.group(6));
          date = DATE_FORMATTER.parse(dateStr);
          // relative path(object)
          path = matcher.group(7);
          // kind
          kind = matcher.group(8);
          // version
          version = matcher.group(9);
          // operation
          operation = matcher.group(10);
          // event
          event = matcher.group(11);
          // comment
          comment = matcher.group(12);
          // activity
          activity = matcher.group(13);
          // all parsed
          isValid = true;
          return;

        } catch (Exception e) {
          throw new IOException(e.getMessage());

        }
      }
      throw new IOException(String.format("The \"%s\" output line is not matched", line));
    }

    boolean isValid() {
      return isValid;
    }

  }

  static class ChangeParser {

    static final String UPDATED_TOKEN = "Updated:";
    static final String NEW_TOKEN = "New:";
    static final String UNLOADED_DELETED_TOKEN = "UnloadDeleted:";

    private boolean isDeletion;

    private boolean isChange;

    private boolean isAddition;

    private String myLocalPath;

    private String myVersionBefor;

    private String myVersionAfter;

    static boolean accept(String line) {
      line = line.trim();
      if (line.startsWith(UPDATED_TOKEN) || line.startsWith(NEW_TOKEN) || line.startsWith(UNLOADED_DELETED_TOKEN)) {
        return true;
      }
      return false;
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
      line = line.substring(tokenLength, line.length()).trim();
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
        String versionsPart = line.substring(versionBeforStartIdx + 1, line.length()).trim();
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
          myTag = getRest(String.format("%s %s", split[0], split[1]), TAG_TOKEN);// as
          // far
          // cleartool
          // put
          // the
          // comment
          // next
          // to
          // the
          // tag
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

  }

  /*
Tag: buildagent_null_vcsroot_1_kdonskov_swiftteams_view "Clone of the kdonskov_swiftteams_view view"
  Global path: \\ruspv-win2003-c\ccstg_c\views\SWIFTTEAMS\kdonskov\kdonskov_swiftteams_view.1.vws
  Server host: ruspv-win2003-c
  Region: swiftteams
  Active: NO
  View tag uuid:d0f2f25d.eeba4c53.82fb.b5:d9:f5:54:88:8d
View on host: ruspv-win2003-c
View server access path: c:\ClearCase_Storage\views\SWIFTTEAMS\kdonskov\kdonskov_swiftteams_view.1.vws
View uuid: d0f2f25d.eeba4c53.82fb.b5:d9:f5:54:88:8d
View attributes: snapshot
View owner: SWIFTTEAMS\kdonskov    
   */
  static class ViewParser extends VobParser {
    
    static final Pattern VIEW_VIEW_TAG_UID_PATTERN = Pattern.compile("View tag uuid: (.*)");
    static final Pattern VIEW_UID_PATTERN = Pattern.compile("View uuid: (.*)");    

    private String myUUID;

    protected ViewParser(String[] stdout) {
      super(stdout);
      for(String line : stdout){
        final Matcher uidMatcher = VIEW_UID_PATTERN.matcher(line.trim());
        if(uidMatcher.matches()){
          myUUID = uidMatcher.group(1);
        }
      }
    }

    public String getUUID() {
      return myUUID;
    }

  }

  static ChangeParser[] setConfigSpecs(File myLocalPath, ArrayList<String> configSpecs) throws IOException, InterruptedException {
    final File cffile = new File(String.format("config_specs_%s", System.currentTimeMillis()));
    try {
      final FileWriter writer = new FileWriter(cffile);
      for (String spec : configSpecs) {
        writer.write(spec);
        writer.write("\n");
      }
      writer.flush();
      writer.close();
      /**
       * set NOTE: must be executed under root hierarchy of Snapshot View
       */
      // "-overwrite" does not support by 2003 final String command =
      // String.format("cleartool setcs -overwrite \"%s\"",
      // cffile.getAbsolutePath());
      final String command = String.format(CMD_SETCS, getCleartoolExecutable(), cffile.getAbsolutePath());
      try {
        Util.execAndWait(command, myLocalPath);
        return new ChangeParser[0];// should not reach there

      } catch (Exception e) {
        // as far setcs writes log file name to stderr have to catch the issue
        String message = e.getMessage().trim();
        if (message.startsWith("Log has been written to")) {
          int firstQuotaIdx = message.indexOf("\"");
          int secondQuotaIdx = message.indexOf("\"", firstQuotaIdx + 1);
          final String absolutePath = message.substring(firstQuotaIdx + 1, secondQuotaIdx);
          LOG.debug(String.format("Got update's log file: %s", absolutePath));
          return parseUpdateOut(new FileInputStream(absolutePath));

        } else {
          throw new IOException(e.getMessage());
        }
      }

    } finally {
      cffile.delete();
    }

  }

  static List<String> getConfigSpecs(final String viewTag) throws IOException, InterruptedException {
    final String command = String.format(CMD_CATCS, getCleartoolExecutable(), viewTag);
    final String[] result = Util.execAndWait(command);
    return Arrays.asList(result);
  }

  static void checkout(File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format(CMD_CHECKOUT, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void mkelem(File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format(CMD_MKELEM, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void checkin(File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format(CMD_CHECKIN, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void rmname(File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format(CMD_RMNAME, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void rmelem(File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format(CMD_RMELEM, getCleartoolExecutable(), reason, file.getAbsolutePath()), root);
  }

  static void rmver(File root, File file, String version, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format(CMD_RMVER, getCleartoolExecutable(), version, reason, file.getAbsolutePath()), root);
  }

}
