package org.jetbrains.teamcity.vcs.clearcase;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

public class CTool {
  
  private static final Logger LOG = Logger.getLogger(CTool.class);  
  
  private static String ourSessionUser;
  private static String ourSessionPassword;
  
  static final List<String> DEFAULT_CONFIG_SPECS = Collections.unmodifiableList(new ArrayList<String>() {
    private static final long serialVersionUID = 1L;
    {
      add("element * CHECKEDOUT");
      add("element * /main/LATEST");
    }
  });

  /**
   * the account uses for PsExec execution only  
   */
  public static void setCredential(final String user, final String password) {
    ourSessionUser = user;
    ourSessionPassword= password;
  }

  static VobObjectResult createVob(final String tag, final String reason) throws IOException, InterruptedException {
    /**
     * check the Tag already exists
     */
    if(isVobExists(tag)){
      throw new IOException(String.format("The VOB \"%s\" already exists", tag));
    }
    final String command = String.format("cleartool mkvob -tag \\%s -c \"%s\" -stgloc -auto", tag, reason);
    return new VobObjectResult(Util.execAndWait(command));
  }
  
  private static boolean isVobExists(String tag) {
    try {
      Util.execAndWait(String.format("cleartool lsvob \\%s", tag));
      return true;
    } catch (Exception e) {
        return false; 
    }
  }
  
  public static void syncTime() throws IOException, InterruptedException {
//    final String host = getHost(getStorageLocation());
//    final String command = String.format("net time \\\\%s /set /Q", host);
//    Util.execAndWait(command);
  }
  

  static VobObjectResult importVob(final String tag, final File dump, final String reason) throws IOException, InterruptedException, CCException {
    final long timeStamp = System.currentTimeMillis();
    CCStorage anyStorage = null;
    File silentCmd = null;
    try {
      //looking for any VOB for storage detection
      CCStorage[] availableVobs = new CCRegion("any").getStorages();
      if (availableVobs.length == 0) {
        throw new CCException("No one VOB Storage found");
      }
      anyStorage = availableVobs[0];      
      final String uploadCommand = String.format("xcopy /Y \"%s\" %s", dump, anyStorage.getGlobalPath());//TODO: use timestamp in file name

      /**
       * upload replica to target host. it required by replica's import
       */
      Util.execAndWait(uploadCommand);
      /**
       * run command using psexec
       * make sure the user account has administrative privileges on target host
       */
      
      silentCmd = new File(String.format("import_replica_%s.cmd", timeStamp));
      silentCmd.createNewFile();
      final FileWriter writer = new FileWriter(silentCmd);
      writer.write("@echo off\n");
      writer.write("echo yes>yes.txt\n");
      writer.write(String.format("multitool mkreplica -import -workdir %s -c \"%s\" -tag \\%s -stgloc -auto -npreserve %s 2>&1 1>>c:\\replica.log 0<yes.txt\n", 
          "c:\\rep.tmp",
          reason,
          tag,
          String.format("%s\\%s", anyStorage.getGlobalPath(), dump.getName())));//TODO: use timestamp in file name
      writer.write("del /F /Q yes.txt\n");
      writer.close();
      
      final String command;
      
      if(ourSessionUser != null){
        //the tool was logged in
        command = String.format("psexec \\\\%s -u %s -p %s -c %s", 
            anyStorage.getServerHost(),
            ourSessionUser,
            ourSessionPassword,
            silentCmd.getAbsolutePath());
      } else {
        //use current credentials
        command = String.format("psexec \\\\%s -c %s", 
            anyStorage.getServerHost(),
            silentCmd.getAbsolutePath());
      }
      try{
        Util.execAndWait(command);
      } catch (Exception e){
        //psexec writes own output to stderr
      }
      //read VOB properties
      final VobObjectResult vobObjectResult = new VobObjectResult(Util.execAndWait(String.format("cleartool lsvob -long \\%s", tag)));
      if (vobObjectResult.getGlobalPath() == null) {
        throw new IOException(String.format("Could not create %s VOB", tag));
      }
      return vobObjectResult;

    } finally {
      //cleanup
      if(silentCmd!=null){ 
        silentCmd.delete();
      }
      if(anyStorage != null){
        Util.execAndWait(String.format("cmd /c del /F /Q \"%s\\%s\"", anyStorage.getGlobalPath(), dump.getName()));
      }
    }
    
  }
    
  static void dropVob(String globalPath) throws IOException, InterruptedException {
    Util.execAndWait(String.format("cleartool rmvob -force %s", globalPath));
    LOG.debug(String.format("The Vob \"%s\" has been dropt", globalPath));
  }
  
  static void dropView(String globalPath) throws IOException, InterruptedException {
    Util.execAndWait(String.format("cleartool rmview -force %s", globalPath));
    LOG.debug(String.format("The View \"%s\" has been dropt", globalPath));
  }
  
  static VobObjectResult createSnapshotView(String tag, File path, String reason) throws IOException, InterruptedException {
    path.getParentFile().mkdirs();
    final String command = String.format("cleartool mkview -snapshot -tag %s -tcomment \"%s\" \"%s\"", tag, reason, path.getAbsolutePath());
    return new VobObjectResult(Util.execAndWait(command));
  }
  
  static void update(final File path, final Date to) throws IOException, InterruptedException {
    Util.execAndWait("cleartool update -force -overwrite", getFullEnvp(new String[] { "CCASE_NO_LOG=true" }), path);
  }
  
  private static String[] getFullEnvp(String[] extraEnvp) {
    final ArrayList<String> out = new ArrayList<String>();
    for(Map.Entry<String, String> sysEnvp : System.getenv().entrySet()){
      out.add(String.format("%s=%s", sysEnvp.getKey(), sysEnvp.getValue()));
    }
    for(String extraEnv : extraEnvp){
      out.add(extraEnv);
    }
    return out.toArray(new String[out.size()]);
  }

  static VobResultParser[] lsVob () throws IOException, InterruptedException {
    final String command = "cleartool lsvob -long"; 
    final String[] stdOut = Util.execAndWait(command);
    final ArrayList<String> buffer = new ArrayList<String>();
    final ArrayList<VobResultParser> out = new ArrayList<VobResultParser>();
    for(String line : stdOut){
      final String trim = line.trim();
      if (trim.startsWith(VobResultParser.TAG_TOKEN) && !buffer.isEmpty()){
        //reach the next section
        out.add(new VobResultParser(buffer.toArray(new String[buffer.size()])));
        buffer.clear();
      }
      buffer.add(trim);
    }
    out.add(new VobResultParser(buffer.toArray(new String[buffer.size()])));// do not forget the last
    return out.toArray(new VobResultParser[out.size()]);
  }
  
  static StorageParser[] lsStgLoc() throws IOException, InterruptedException {
    final String command = "cleartool lsstgloc -vob -long"; 
    final String[] stdOut = Util.execAndWait(command);
    final ArrayList<String> buffer = new ArrayList<String>();
    final ArrayList<StorageParser> out = new ArrayList<StorageParser>();
    for(String line : stdOut){
      final String trim = line.trim();
      if (trim.startsWith(StorageParser.NAME_TOKEN) && !buffer.isEmpty()){
        //reach the next section
        out.add(new StorageParser(buffer.toArray(new String[buffer.size()])));
        buffer.clear();
      }
      buffer.add(trim);
    }
    out.add(new StorageParser(buffer.toArray(new String[buffer.size()])));// do not forget the last
    return out.toArray(new StorageParser[out.size()]);
  }
  
  static ViewParser[] lsView() throws IOException, InterruptedException {
    final String command = "cleartool lsview -long"; 
    final String[] stdOut = Util.execAndWait(command);
    final ArrayList<String> buffer = new ArrayList<String>();
    final ArrayList<ViewParser> out = new ArrayList<ViewParser>();
    for(String line : stdOut){
      final String trim = line.trim();
      if (trim.startsWith(ViewParser.TAG_TOKEN) && !buffer.isEmpty()){
        //reach the next section
        out.add(new ViewParser(buffer.toArray(new String[buffer.size()])));
        buffer.clear();
      }
      buffer.add(trim);
    }
    out.add(new ViewParser(buffer.toArray(new String[buffer.size()])));// do not forget the last
    return out.toArray(new ViewParser[out.size()]);
  }
  
  interface ICCOutputParser {
    String[] getStdout();
  }
  
  static class VobObjectResult implements ICCOutputParser {
    
    private static final String HOST_LOCAL_PATH = "Host-local path";
    private static final String GLOBAL_PATH = "Global path";
    
    private String myHostLocalPath;
    private String myGlobalPath;
    private String[] myStdOut;

    VobObjectResult(String[] stdout) {
      myStdOut = stdout;
      for (String line : myStdOut) {
        if (line.trim().startsWith(HOST_LOCAL_PATH)) {
          myHostLocalPath = line.substring(line.indexOf(":") + 1, line.length()).trim();

        } else if (line.trim().startsWith(GLOBAL_PATH)) {
          myGlobalPath = line.substring(line.indexOf(":") + 1, line.length()).trim();

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
  
  public static abstract class AbstractCCParser implements ICCOutputParser {

    private String[] myStdOut;

    protected AbstractCCParser(String[] stdout){
      myStdOut = stdout;
    }
    
    public String[] getStdout() {
      return myStdOut;
    }

    protected String getRest(String trim, String tagToken) {
      return trim.substring(tagToken.length(), trim.length()).trim();
    }
  
  }
  
  static class  StorageParser extends AbstractCCParser {
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
  
  static class  VobResultParser extends AbstractCCParser {
    
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

    VobResultParser(String[] stdout) {
      super(stdout);
      for (String line : stdout) {
        final String trim = line.trim();
        if (trim.startsWith(TAG_TOKEN)) {
          final String[] split = trim.split(" +");
          myTag = getRest(String.format("%s %s", split[0], split[1]), TAG_TOKEN);//as far cleartool put the comment next to the tag
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
  
  static class ViewParser extends VobResultParser {

    protected ViewParser(String[] stdout) {
      super(stdout);
      
    }
    
  }
  
  static void setConfigSpecs(File myLocalPath, ArrayList<String> configSpecs) throws IOException, InterruptedException {
    final File cffile = new File(String.format("config_specs_%s", System.currentTimeMillis()));
    try {
      final FileWriter writer = new FileWriter(cffile);
      for(String spec: configSpecs){
        writer.write(spec);
        writer.write("\n");
      }
      writer.flush();
      writer.close();
      /**
       * set 
       * NOTE: must be executed under root hierarchy of Snapshot View
       */
      Util.execAndWait(String.format("cleartool setcs \"%s\"", cffile.getAbsolutePath()), getFullEnvp(new String[] { "CCASE_NO_LOG=true" }), myLocalPath);
      
    } finally {
      cffile.delete();
      
    }

  }
  
  static List<String> getConfigSpecs(final String viewTag) throws IOException, InterruptedException {
    final String command = String.format("cleartool catcs -tag %s", viewTag);
    final String[] result = Util.execAndWait(command);
    return Arrays.asList(result);
  }

  static void checkout (File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format("cleartool checkout -c \"%s\" \"%s\"", reason, file.getAbsolutePath()), root);
  }
  
  static void mkelem (File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format("cleartool mkelem -nwarn -c \"%s\" \"%s\"", reason, file.getAbsolutePath()), root);
  }
  
  static void checkin (File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format("cleartool checkin -identical -nwarn -c \"%s\" \"%s\"", reason, file.getAbsolutePath()), root);
  }
  
  static void rmname (File root, File file, String reason) throws IOException, InterruptedException {
    Util.execAndWait(String.format("cleartool rmname -force -c \"%s\" \"%s\"", reason, file.getAbsolutePath()), root);
  }

  
}
