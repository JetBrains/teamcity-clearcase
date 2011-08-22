package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import java.util.Date;
import org.jetbrains.annotations.NotNull;

/**
 * @author Maxim.Manuylov
 *         Date: 8/19/11
 */
public abstract class DateRevision extends Revision {
  @NotNull
  public abstract Date getDate();
}
