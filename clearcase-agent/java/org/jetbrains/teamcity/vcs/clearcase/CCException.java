package org.jetbrains.teamcity.vcs.clearcase;

public class CCException extends Exception {

  private static final long serialVersionUID = 1L;
  
  public CCException(Exception e) {
    super(e);
  }

  public CCException(String message) {
    super(message);
  }


}
