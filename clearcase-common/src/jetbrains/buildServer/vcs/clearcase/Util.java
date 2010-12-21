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

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import com.intellij.execution.configurations.ParametersList;

public class Util {

  private static final Logger LOG = Logger.getLogger(Util.class);

  /**
   * @param command
   * @return
   */
  private static Pattern EXE_NOT_FOUND_PATTERN = Pattern.compile("(.*)error=2,(.*)");

  //

  public static boolean canRun(String executable) {
    try {
      execAndWait(executable);
    } catch (Exception e) {
      LOG.debug(e);
      final Matcher matcher = EXE_NOT_FOUND_PATTERN.matcher(e.getMessage().trim());
      if (matcher.matches()) {
        LOG.debug(String.format("Cannot run \"%s\": executable not found", executable));
        return false;
      }
    }
    return true;
  }

  public static String[] execAndWait(String command) throws IOException, InterruptedException {
    return execAndWait(command, new File("."));
  }

  public static String[] execAndWait(String command, String[] envp) throws IOException, InterruptedException {
    return execAndWait(command, envp, new File("."));
  }

  public static String[] execAndWait(String command, File dir) throws IOException, InterruptedException {
    return execAndWait(command, null, dir);
  }

  public static String[] execAndWait(String command, String[] envp, File dir) throws IOException, InterruptedException {
    LOG.debug(String.format("Executing command: \"%s\" in %s", command, dir));
    Process process = Runtime.getRuntime().exec(makeArguments(command), envp, dir);
    process.getOutputStream().close();
    final StringBuffer errBuffer = new StringBuffer();
    final StringBuffer outBuffer = new StringBuffer();
    final Thread errReader = pipe(process.getErrorStream(), null, errBuffer);
    final Thread outReader = pipe(process.getInputStream(), null, outBuffer);
    int result = process.waitFor();
    // wait for readers to finish...
    errReader.join();
    outReader.join();
    process.getErrorStream().close();
    process.getInputStream().close();
    if (LOG.isDebugEnabled()) {
      LOG.debug(String.format("Command stdout:\n%s", outBuffer.toString()));
    }
    if (result != 0 || (errBuffer != null && errBuffer.length() > 0)) {
      LOG.debug(String.format("Command stderr:\n%s", errBuffer.toString()));
      throw new IOException(String.format("%s: command: {\"%s\" in: \"%s\"}", errBuffer.toString().trim(), command.trim(), dir.getAbsolutePath()));
    }
    return outBuffer.toString().split("\n+");
  }

  public static String[] makeArguments(final @NotNull String command) {
    return ParametersList.parse(command);
  }

  private static Thread pipe(final InputStream inStream, final PrintStream outStream, final StringBuffer out) {
    Thread reader = new Thread(new Runnable() {
      public void run() {
        try {
          byte[] buffer = new byte[1];
          int in;
          while ((in = inStream.read()) > -1) {// use
            // "final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));"
            // instead
            if (outStream != null) {
              outStream.write(in);
            }
            buffer[0] = (byte) in;
            out.append(new String(buffer));
          }

        } catch (IOException e) {
          LOG.error(e.getMessage(), e);
        }
      }

    });
    reader.start();
    return reader;
  }

  public static String createLoadRuleForVob(final CCVob vob) {
    return String.format("load %s", normalizeVobTag(vob.getTag()));
  }

  static String normalizeVobTag(final String tag) {
    return tag.startsWith("\\") || tag.startsWith("/") ? tag : String.format("\\%s", tag.trim());
  }

  public static java.io.File createTempFile() throws IOException {
    return java.io.File.createTempFile("clearcase-agent", "tmp");
  }

  public static class Finder {

    public static CCSnapshotView findView(CCRegion region, String viewTag) throws CCException {
      for (CCSnapshotView view : region.getViews()) {
        if (view.getTag().equals(viewTag)) {
          return view;
        }
      }
      return null;
    }
  }

  public static class FileSystem {

    public static class CopyJob {

      private final Source[] sources;
      private final String[] destinations;
      private List<DirectoryInfo> directoryInfos;

      public CopyJob(Source[] sources, String[] destinations) {
        this.sources = sources;
        this.destinations = destinations;
      }

      public Source[] getSources() {
        return sources;
      }

      public String[] getDestinations() {
        return destinations;
      }

      public void setDirectoryInfos(List<DirectoryInfo> directoryInfos) {
        this.directoryInfos = directoryInfos;
      }

      public List<DirectoryInfo> getDirectoryInfos() {
        return directoryInfos;
      }
    }

    public static class DirectoryInfo {

      private final File baseDirectory;
      private final List<File> files;
      private final long byteCount;

      public DirectoryInfo(File baseDirectory, List<File> files, long byteCount) {
        this.baseDirectory = baseDirectory;
        this.files = files;
        this.byteCount = byteCount;
      }

      public File getBaseDirectory() {
        return baseDirectory;
      }

      public List<File> getFiles() {
        return files;
      }

      public long getByteCount() {
        return byteCount;
      }
    }

    public static class FileCopier {

      public final static String FILE_PROPERTY = "file";
      public final static String BYTE_COUNTER_PROPERTY = "byte_counter";
      public final static String STATE_PROPERTY = "state";

      public enum State {

        START, CHECKING_SOURCE, COPYING, END
      }

      private State state = State.START;
      private final static Logger LOGGER = Logger.getLogger(FileCopier.class.getName());
      // the copy intervall we want to get in ms
      private static final int WANTED_TIME = 1000;
      private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);
      private long byteCount;
      private long oldCopiedBytes;
      private long copiedBytes;
      private final static NumberFormat NUMBER_FORMAT = NumberFormat.getInstance();
      private long position;
      private long sourceLength;
      private long slice = 1048576; // 1 MiB
      private long transferVolume;
      private long sliceStartTime;
      private CyclicBarrier barrier;

      public void addPropertyChangeListener(String property, PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(property, listener);
      }

      public void removePropertyChangeListener(String property, PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(property, listener);
      }

      public long getByteCount() {
        return byteCount;
      }

      public long getCopiedBytes() {
        return copiedBytes;
      }

      public void reset() {
        State previousState = state;
        state = State.START;
        propertyChangeSupport.firePropertyChange(STATE_PROPERTY, previousState, state);
      }

      public void copy(CopyJob... copyJobs) throws IOException {
        byteCount = 0;
        copiedBytes = 0;

        // feed our property change listeners
        State previousState = state;
        state = State.CHECKING_SOURCE;
        propertyChangeSupport.firePropertyChange(STATE_PROPERTY, previousState, state);

        // scan all sources of all copyJobs and store the directoryInfos
        int fileCount = 0;
        for (CopyJob copyJob : copyJobs) {
          if (copyJob == null) {
            continue;
          }
          Source[] sources = copyJob.getSources();
          List<DirectoryInfo> directoryInfos = new ArrayList<DirectoryInfo>();
          for (Source source : sources) {
            File baseDirectory = source.getBaseDirectory();
            int baseDirectoryPathLength = 0;
            String baseDirectoryPath = baseDirectory.getPath();
            if (baseDirectoryPath.endsWith(File.separator)) {
              // baseDirectory is a file system root, e.g.
              // "/" or "C:\"
              baseDirectoryPathLength = baseDirectoryPath.length();
            } else {
              // baseDirectory is a normal directory, e.g.
              // "/etc" or "C:\test"
              baseDirectoryPathLength = baseDirectoryPath.length() + 1;
            }
            DirectoryInfo tmpInfo = expand(baseDirectoryPathLength, baseDirectory, source.getPattern(), source.isRecursive());
            if (tmpInfo != null) {
              directoryInfos.add(tmpInfo);
              byteCount += tmpInfo.getByteCount();
              fileCount += tmpInfo.getFiles().size();
            }
          }
          copyJob.setDirectoryInfos(directoryInfos);
          if (LOGGER.isDebugEnabled()) {
            StringBuilder stringBuilder = new StringBuilder("source files:\n");
            for (DirectoryInfo directoryInfo : directoryInfos) {
              stringBuilder.append("source files in base directory ");
              stringBuilder.append(directoryInfo.getBaseDirectory());
              stringBuilder.append(":\n");
              for (File sourceFile : directoryInfo.getFiles()) {
                stringBuilder.append(sourceFile.isFile() ? "f " : "d ");
                stringBuilder.append(sourceFile.getPath());
                stringBuilder.append('\n');
              }
            }
            LOGGER.info(stringBuilder.toString());
          }
        }

        if (fileCount == 0) {
          LOGGER.info("there are no files to copy");
          return;
        }

        // do all known sanity checks
        for (CopyJob copyJob : copyJobs) {
          // skip empty jobs
          if (copyJob == null) {
            continue;
          }
          // get number of source files in this job
          List<DirectoryInfo> directoryInfos = copyJob.getDirectoryInfos();
          int sourceCount = 0;
          for (DirectoryInfo directoryInfo : directoryInfos) {
            sourceCount += directoryInfo.getFiles().size();
          }
          // skip empty jobs
          if (sourceCount == 0) {
            continue;
          }

          String[] destinations = copyJob.getDestinations();
          for (String destination : destinations) {
            File destinationFile = new File(destination);
            if (destinationFile.isFile()) {
              if (sourceCount == 1) {
                File sourceFile = directoryInfos.get(0).getFiles().get(0);
                if (sourceFile.isDirectory()) {
                  throw new IOException("can not overwrite file \"" + destinationFile + "\" with directory \"" + sourceFile + "\"");
                }
              } else {
                StringBuilder errorMessage = new StringBuilder("can not copy several files to another file\n" + " sources:");
                for (DirectoryInfo directoryInfo : directoryInfos) {
                  List<File> files = directoryInfo.getFiles();
                  for (File file : files) {
                    errorMessage.append("  " + file.getPath());
                  }
                }
                errorMessage.append(" destination: " + destinationFile.getPath());
                throw new IOException(errorMessage.toString());
              }
            }
          }
        }

        // feed our property change listeners
        previousState = state;
        state = State.COPYING;
        propertyChangeSupport.firePropertyChange(STATE_PROPERTY, previousState, state);

        // execute all copy jobs
        for (CopyJob copyJob : copyJobs) {
          // skip empty copy jobs
          if (copyJob == null) {
            continue;
          }

          for (DirectoryInfo directoryInfo : copyJob.getDirectoryInfos()) {
            for (File sourceFile : directoryInfo.getFiles()) {
              File[] destinationFiles = getDestinationFiles(directoryInfo.getBaseDirectory(), sourceFile, copyJob.getDestinations());
              if (sourceFile.isDirectory()) {
                // make target directories (sequentially)
                for (File destinationFile : destinationFiles) {
                  if (destinationFile.exists()) {
                    if (destinationFile.isDirectory()) {
                      LOGGER.info("Directory \"" + destinationFile + "\" already exists");
                    } else {
                      throw new IOException("can not overwrite " + "file \"" + destinationFile + "\" with directory \"" + sourceFile + "\"");
                    }
                  } else {
                    LOGGER.info("Creating directory \"" + destinationFile + "\"");
                    if (!destinationFile.mkdirs()) {
                      throw new IOException("Could not create directory \"" + destinationFile + "\"");
                    }
                  }
                }
              } else {
                // create target files in parrallel
                copyFile(sourceFile, destinationFiles);
              }
            }
          }
        }

        if (oldCopiedBytes != copiedBytes) {
          // need to fire one last time...
          // (last slice was not fully used)
          propertyChangeSupport.firePropertyChange(BYTE_COUNTER_PROPERTY, oldCopiedBytes, copiedBytes);
        }
        previousState = state;
        state = State.END;
        propertyChangeSupport.firePropertyChange(STATE_PROPERTY, previousState, state);
      }

      private File[] getDestinationFiles(File baseDirectory, File sourceFile, String[] destinations) {
        int destinationCount = destinations.length;
        File[] destinationFiles = new File[destinationCount];
        for (int i = 0; i < destinationCount; i++) {
          File destinationFile = new File(destinations[i]);
          if (destinationFile.isDirectory()) {
            // remap target
            int baseLength = baseDirectory.getPath().length();
            String filePath = sourceFile.getPath();
            String destinationPath = filePath.substring(baseLength);
            destinationFiles[i] = new File(destinationFile, destinationPath);
          } else {
            destinationFiles[i] = destinationFile;
          }
        }
        return destinationFiles;
      }

      private DirectoryInfo expand(int baseDirectoryPathLength, File currentDirectory, Pattern pattern, boolean recursive) {

        LOGGER.info("\n\tcurrent directory: \"" + currentDirectory + "\"\n\tpattern: \"" + pattern + "\"");

        // feed the listeners
        propertyChangeSupport.firePropertyChange(FILE_PROPERTY, null, currentDirectory);

        if (!currentDirectory.exists()) {
          LOGGER.warn(currentDirectory + " does not exist");
          return null;
        }

        if (!currentDirectory.isDirectory()) {
          LOGGER.warn(currentDirectory + " is no directory");
          return null;
        }

        if (!currentDirectory.canRead()) {
          LOGGER.warn("can not read " + currentDirectory);
          return null;
        }

        if (pattern == null) {
          throw new IllegalArgumentException("pattern must not be null");
        }

        LOGGER.debug("recursing directory " + currentDirectory);
        long tmpByteCount = 0;
        List<File> files = new ArrayList<File>();
        for (File subFile : currentDirectory.listFiles()) {

          // check if subfile matches
          String relativePath = subFile.getPath().substring(baseDirectoryPathLength);
          if (pattern.matcher(relativePath).matches()) {
            LOGGER.debug(subFile + " matches");
            if (subFile.isDirectory()) {
              // copy directories itself only when using recursive mode
              if (recursive) {
                files.add(subFile);
              }
            } else {
              files.add(subFile);
              tmpByteCount += subFile.length();
            }
          } else {
            LOGGER.debug(subFile + " does not match");
          }

          // recurse directories
          if (subFile.isDirectory()) {
            if (recursive) {
              DirectoryInfo tmpInfo = expand(baseDirectoryPathLength, subFile, pattern, recursive);
              if (tmpInfo != null) {
                files.addAll(tmpInfo.getFiles());
                tmpByteCount += tmpInfo.getByteCount();
              }
            }
          }
        }
        return new DirectoryInfo(currentDirectory, files, tmpByteCount);
      }

      private void copyFile(File source, File... destinations) throws IOException {

        // some initial logging
        if (LOGGER.isDebugEnabled()) {
          StringBuilder stringBuilder = new StringBuilder();
          stringBuilder.append("Copying file \"");
          stringBuilder.append(source.toString());
          stringBuilder.append("\" to the following destinations:\n");
          for (File destination : destinations) {
            stringBuilder.append(destination.getPath());
            stringBuilder.append('\n');
          }
          LOGGER.info(stringBuilder.toString());
        }

        // ensure that all destination files exist before starting the transfer
        // processing
        for (File destination : destinations) {
          if (!destination.exists()) {
            destination.getParentFile().mkdirs();
            destination.createNewFile();
          }
        }

        // quick return when source is an empty file
        sourceLength = source.length();
        if (sourceLength == 0) {
          return;
        }

        // create a Transferrer thread for every destination
        int destinationCount = destinations.length;
        final Transferrer[] transferrers = new Transferrer[destinationCount];
        for (int i = 0; i < destinationCount; i++) {
          transferrers[i] = new Transferrer(new FileInputStream(source).getChannel(), new FileOutputStream(destinations[i]).getChannel());
        }

        barrier = new CyclicBarrier(destinationCount, new Runnable() {

          public void run() {

            // inform property listeners about copied data volume
            position += transferVolume;
            copiedBytes += transferVolume;
            propertyChangeSupport.firePropertyChange(BYTE_COUNTER_PROPERTY, oldCopiedBytes, copiedBytes);
            oldCopiedBytes = copiedBytes;

            // determine next slice size before releasing the barrier
            long stop = System.currentTimeMillis();
            long time = stop - sliceStartTime;
            LOGGER.debug("time = " + NUMBER_FORMAT.format(time) + " ms");
            if (time != 0) {
              // bandwidth = transferVolume / time
              // newSlice = bandwith * WANTED_TIME
              long newSlice = (transferVolume * WANTED_TIME) / time;
              // just using newSlice here leads to overmodulation
              // doubling or halving is the slower (and probably better)
              // approach
              long doubleSlice = 2 * slice;
              long halfSlice = slice / 2;
              if (newSlice > doubleSlice) {
                slice = doubleSlice;
              } else if ((newSlice < halfSlice) && (halfSlice > 0)) {
                slice = halfSlice;
              }
              transferVolume = Math.min(slice, sourceLength - position);
              LOGGER.debug("\nslice = " + NUMBER_FORMAT.format(slice) + " Byte\ntransferVolume = " + NUMBER_FORMAT.format(transferVolume) + " Byte");
            }
            sliceStartTime = System.currentTimeMillis();
          }
        });

        // start the transfer process
        position = 0;
        transferVolume = Math.min(slice, sourceLength);
        LOGGER.debug("\nslice = " + NUMBER_FORMAT.format(slice) + " Byte\ntransferVolume = " + NUMBER_FORMAT.format(transferVolume) + " Byte");
        sliceStartTime = System.currentTimeMillis();

        ExecutorService executorService = Executors.newCachedThreadPool();
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<Void>(executorService);
        for (Transferrer transferrer : transferrers) {
          completionService.submit(transferrer, null);
        }

        // wait until all transferrers completed their execution
        for (int i = 0; i < destinationCount; i++) {
          try {
            completionService.take();
          } catch (InterruptedException ex) {
            LOGGER.error(ex.getMessage(), ex);
          }
        }
        executorService.shutdown();
      }

      private class Transferrer extends Thread {

        private final FileChannel sourceChannel;
        private final FileChannel destinationChannel;

        public Transferrer(FileChannel sourceChannel, FileChannel destinationChannel) {
          this.sourceChannel = sourceChannel;
          this.destinationChannel = destinationChannel;
        }

        @Override
        public void run() {
          try {
            while (position < sourceLength) {
              // transfer the current slice
              long transferredBytes = 0;
              while (transferredBytes < transferVolume) {
                if (LOGGER.isDebugEnabled()) {
                  LOGGER.debug("position = " + position + ", transferredBytes = " + transferredBytes + ", transferVolume = " + transferVolume);
                }
                long transferSize = destinationChannel.transferFrom(sourceChannel, position, transferVolume - transferredBytes);
                if (LOGGER.isDebugEnabled()) {
                  LOGGER.debug("transferSize = " + transferSize);
                }
                transferredBytes += transferSize;
              }
              // wait for all other Transferrers to finish their slice
              barrier.await();
            }
          } catch (IOException ex) {
            LOGGER.error("could not transfer data", ex);
          } catch (InterruptedException ex) {
            LOGGER.error(null, ex);
          } catch (BrokenBarrierException ex) {
            LOGGER.error(null, ex);
          } finally {
            try {
              sourceChannel.close();
            } catch (IOException ex) {
              LOGGER.debug("could not close destination channel", ex);
            }
            try {
              destinationChannel.close();
            } catch (IOException ex) {
              LOGGER.debug("could not close destination channel", ex);
            }
          }
        }
      }
    }

    public static class Source {

      private final File baseDirectory;
      private final Pattern pattern;
      private final boolean recursive;

      public Source(String fileName) {
        File sourceFile = new File(fileName);
        if (sourceFile.isDirectory()) {
          File parent = sourceFile.getParentFile();
          if (parent == null) {
            // baseFile is the file system root
            baseDirectory = sourceFile;
            pattern = Pattern.compile(".*");
            recursive = true;
          } else {
            baseDirectory = parent;
            pattern = Pattern.compile(sourceFile.getName() + ".*");
            recursive = true;
          }
        } else {
          baseDirectory = sourceFile.getParentFile();
          pattern = Pattern.compile(sourceFile.getName());
          recursive = true;
        }
      }

      public Source(String baseDirectory, String pattern) {
        this(baseDirectory, pattern, true);
      }

      public Source(String baseDirectory, String pattern, boolean recursive) {
        this.baseDirectory = new File(baseDirectory);
        this.pattern = Pattern.compile(pattern);
        this.recursive = recursive;
      }

      public File getBaseDirectory() {
        return baseDirectory;
      }

      public Pattern getPattern() {
        return pattern;
      }

      public boolean isRecursive() {
        return recursive;
      }
    }

  }

  public static void main(String[] args) throws Exception {
    Runtime.getRuntime().exec("cleartool1.exe");
  }

}
