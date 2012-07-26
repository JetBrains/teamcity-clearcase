package jetbrains.buildServer.buildTriggers.vcs.clearcase.process;

import java.io.IOException;
import org.jetbrains.annotations.NotNull;

/**
 * @author maxim.manuylov
 *         Date: 7/26/12
 */
public class ReadTimeoutException extends IOException {
  public ReadTimeoutException(@NotNull final String message) {
    super(message);
  }
}
