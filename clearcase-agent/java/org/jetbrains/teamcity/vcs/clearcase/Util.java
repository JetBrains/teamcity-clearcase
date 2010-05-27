package org.jetbrains.teamcity.vcs.clearcase;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

import org.apache.log4j.Logger;

public class Util {
  
  private static final Logger LOG = Logger.getLogger(Util.class);  
  
  public static String[] execAndWait(String command) throws IOException, InterruptedException {
    return execAndWait(command, new File("."));
  }
  
  public static String[] execAndWait(String command, String[] envp) throws IOException, InterruptedException {
    return execAndWait(command, envp, new File("."));
  }
  
  public static String[] execAndWait(String command, File dir) throws IOException, InterruptedException {
    return execAndWait(command, null, dir);
  }
  
  public static String[] execAndWait(String command, String[] envp, /*String stdin, */File dir) throws IOException, InterruptedException {
    Process process = Runtime.getRuntime().exec(command, envp, dir);
//    if(stdin != null){
//      process.getOutputStream().write(stdin.getBytes());
//      process.getOutputStream().flush();
//    }
    process.getOutputStream().close();
    final StringBuffer errBuffer = new StringBuffer();
    final StringBuffer outBuffer = new StringBuffer();
    pipe(process.getErrorStream(), null, errBuffer);
    pipe(process.getInputStream(), null, outBuffer);
    int result = process.waitFor();
    LOG.debug(outBuffer.toString());
    if (result != 0 || (errBuffer != null && errBuffer.length() > 0)) {
      LOG.error(outBuffer.toString());
      throw new IOException(String.format("%s: command: {\"%s\" in: \"%s\"", errBuffer.toString().trim(), command.trim(), dir.getAbsolutePath()));
    }
    return outBuffer.toString().split("\n+");
  }

  private static void pipe(final InputStream inStream, final PrintStream outStream, final StringBuffer out) {
    Thread reader = new Thread(new Runnable(){
      public void run() {
        try {
          byte[] buffer = new byte[1];
          int in;
          while ((in = inStream.read()) > -1) {//use "final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));" instead
            if(outStream!=null){
              outStream.write(in);
            }
            buffer[0] = (byte)in;
            out.append(new String(buffer));
          }
        } catch (IOException e) {
          LOG.error(e.getMessage(),e);
        }
      }
      
    });
    reader.start();
  }

  static String createLoadRuleForVob(final CCVob vob) {
    return String.format("load \\%s", vob.getTag());
  }
  
  public static File createTempFile() throws IOException {
    return File.createTempFile("clearcase-agent", "tmp");
  }
  
  public static class Finder {
    
    public static CCSnapshotView findView(CCRegion region, String viewTag) throws CCException {
      for(CCSnapshotView view : region.getViews()){
        if(view.getTag().equals(viewTag)){
          return view;
        }
      }
      return null;
    }
  }
  
  
  
}
