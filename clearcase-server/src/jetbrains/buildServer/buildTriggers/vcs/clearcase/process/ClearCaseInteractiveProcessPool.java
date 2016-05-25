/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package jetbrains.buildServer.buildTriggers.vcs.clearcase.process;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import java.io.IOException;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ViewPath;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

public class ClearCaseInteractiveProcessPool {
  @NotNull private static ClearCaseFacade ourProcessExecutor = new ClearCaseFacade() {
    @NotNull
    public ClearCaseInteractiveProcess createProcess(@NotNull final String workingDirectory, @NotNull final GeneralCommandLine generalCommandLine) throws ExecutionException {
      try {
        return ClearCaseInteractiveProcessPool.createProcess(workingDirectory, generalCommandLine.createProcess());
      }
      catch (final ExecutionException e) {
        if (Util.isExecutableNotFoundException(e)) {
          throw new Util.ExecutableNotFoundException(generalCommandLine.getCommandLineString(), e.getMessage()) {
            @Override
            public String getMessage() {
              return Constants.CLIENT_NOT_FOUND_MESSAGE;
            }
          };
        }
        throw e;
      }
    }
  };

  @TestOnly
  public static void setProcessExecutor(@NotNull final ClearCaseFacade executor) {
    ourProcessExecutor = executor;
  }

  public static void doWithProcess(@NotNull final ViewPath viewPath, @NotNull final ProcessRunnable runnable) throws IOException, VcsException {
    doWithProcess(viewPath.getWholePath(), runnable);
  }
  
  public static void doWithProcess(@NotNull final String workingDirectory, @NotNull final ProcessRunnable runnable) throws IOException, VcsException {
    final ClearCaseInteractiveProcess process = createProcess(workingDirectory);
    try {
      runnable.run(process);
    }
    finally {
      process.destroy();
    }
  }

  public static <T> T doWithProcess(@NotNull final String workingDirectory, @NotNull final ProcessComputable<T> computable) throws IOException, VcsException {
    final ClearCaseInteractiveProcess process = createProcess(workingDirectory);
    try {
      return computable.compute(process);
    }
    finally {
      process.destroy();
    }
  }

  @NotNull
  private static ClearCaseInteractiveProcess createProcess(@NotNull final String workingDirectory) throws IOException {
    try {
      return (ClearCaseInteractiveProcess) ourProcessExecutor.createProcess(workingDirectory, createCommandLine(workingDirectory, "-status"));
    }
    catch (final ExecutionException e) {
      final IOException io = new IOException(e.getMessage());
      io.initCause(e);
      throw io;
    }
  }

  @NotNull
  private static GeneralCommandLine createCommandLine(@NotNull final String workingDirectory, @NotNull final String... parameters) {
    final GeneralCommandLine generalCommandLine = new GeneralCommandLine();
    generalCommandLine.setExePath("cleartool");
    generalCommandLine.setWorkDirectory(workingDirectory);
    for (final String parameter : parameters) {
      generalCommandLine.addParameter(parameter);
    }
    return generalCommandLine;
  }

  @NotNull
  private static ClearCaseInteractiveProcess createProcess(@NotNull final String workingDirectory, @NotNull final Process process) {
    return new ClearCaseInteractiveProcess(workingDirectory, process);
  }
  
  public static interface ProcessRunnable {
    void run(@NotNull ClearCaseInteractiveProcess process) throws IOException, VcsException;
  }

  public static interface ProcessComputable<T> {
    T compute(@NotNull ClearCaseInteractiveProcess process) throws IOException, VcsException;
  }
}
