/* -*- mode: jde; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  AvrdudeUploader - uploader implementation using avrdude
  Part of the Arduino project - http://www.arduino.cc/

  Copyright (c) 2004-05
  Hernando Barragan

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
  
  $Id$
*/

package processing.app.debug;

import processing.app.Base;
import processing.app.Preferences;
import processing.app.Serial;
import processing.app.SerialException;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.swing.*;
import gnu.io.*;


public class AvrdudeUploader extends Uploader  {
  public AvrdudeUploader() {
  }

  public boolean uploadUsingPreferences(String buildPath, String className, boolean usingProgrammer)
  throws RunnerException, SerialException {
    this.verbose = verbose;
    Map<String, String> boardPreferences = Base.getBoardPreferences();

    // if no protocol is specified for this board, assume it lacks a 
    // bootloader and upload using the selected programmer.
    if (usingProgrammer || boardPreferences.get("upload.protocol") == null) {
      String programmer = Preferences.get("programmer");
      Target target = Base.getTarget();

      if (programmer.indexOf(":") != -1) {
        target = Base.targetsTable.get(programmer.substring(0, programmer.indexOf(":")));
        programmer = programmer.substring(programmer.indexOf(":") + 1);
      }
      
      Collection params = getProgrammerCommands(target, programmer);
      params.add("-Uflash:w:" + buildPath + File.separator + className + ".hex:i");
      return avrdude(params);
    }

    return uploadViaBootloader(buildPath, className);
  }
  
  private boolean uploadViaBootloader(String buildPath, String className)
  throws RunnerException, SerialException {
    Map<String, String> boardPreferences = Base.getBoardPreferences();
    List commandDownloader = new ArrayList();
    String protocol = boardPreferences.get("upload.protocol");
    
    // avrdude wants "stk500v1" to distinguish it from stk500v2
    if (protocol.equals("stk500"))
      protocol = "stk500v1";
    commandDownloader.add("-c" + protocol);
    commandDownloader.add(
      "-P" + (Base.isWindows() ? "\\\\.\\" : "") + Preferences.get("serial.port"));
    commandDownloader.add(
      "-b" + Integer.parseInt(boardPreferences.get("upload.speed")));
    commandDownloader.add("-D"); // don't erase
    commandDownloader.add("-Uflash:w:" + buildPath + File.separator + className + ".hex:i");

    if (boardPreferences.get("upload.disable_flushing") == null ||
        boardPreferences.get("upload.disable_flushing").toLowerCase().equals("false")) {
      flushSerialBuffer();
    }

    return avrdude(commandDownloader);
  }
  
  public boolean burnBootloader() throws RunnerException {
    String programmer = Preferences.get("programmer");
    Target target = Base.getTarget();
    if (programmer.indexOf(":") != -1) {
      target = Base.targetsTable.get(programmer.substring(0, programmer.indexOf(":")));
      programmer = programmer.substring(programmer.indexOf(":") + 1);
    }
    return burnBootloader(getProgrammerCommands(target, programmer));
  }
  
  private Collection getProgrammerCommands(Target target, String programmer) {
    Map<String, String> programmerPreferences = target.getProgrammers().get(programmer);
    List params = new ArrayList();
    params.add("-c" + programmerPreferences.get("protocol"));
    
    if ("usb".equals(programmerPreferences.get("communication"))) {
      params.add("-Pusb");
    } else if ("serial".equals(programmerPreferences.get("communication"))) {
      params.add("-P" + (Base.isWindows() ? "\\\\.\\" : "") + Preferences.get("serial.port"));
      if (programmerPreferences.get("speed") != null) {
	params.add("-b" + Integer.parseInt(programmerPreferences.get("speed")));
      }
    }
    // XXX: add support for specifying the port address for parallel
    // programmers, although avrdude has a default that works in most cases.
    
    if (programmerPreferences.get("force") != null &&
        programmerPreferences.get("force").toLowerCase().equals("true"))
      params.add("-F");
    
    if (programmerPreferences.get("delay") != null)
      params.add("-i" + programmerPreferences.get("delay"));
    
    return params;
  }
  
  protected boolean burnBootloader(Collection params)
  throws RunnerException {
    Map<String, String> boardPreferences = Base.getBoardPreferences();
    List fuses = new ArrayList();
    fuses.add("-e"); // erase the chip
    if (boardPreferences.get("bootloader.unlock_bits") != null)
      fuses.add("-Ulock:w:" + boardPreferences.get("bootloader.unlock_bits") + ":m");
    if (boardPreferences.get("bootloader.extended_fuses") != null)
      fuses.add("-Uefuse:w:" + boardPreferences.get("bootloader.extended_fuses") + ":m");
    fuses.add("-Uhfuse:w:" + boardPreferences.get("bootloader.high_fuses") + ":m");
    fuses.add("-Ulfuse:w:" + boardPreferences.get("bootloader.low_fuses") + ":m");

    if (!avrdude(params, fuses))
      return false;

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {}
    
    Target t;
    List bootloader = new ArrayList();
    String bootloaderPath = boardPreferences.get("bootloader.path");
    
    if (bootloaderPath != null) {
      if (bootloaderPath.indexOf(':') == -1) {
        t = Base.getTarget(); // the current target (associated with the board)
      } else {
        String targetName = bootloaderPath.substring(0, bootloaderPath.indexOf(':'));
        t = Base.targetsTable.get(targetName);
        bootloaderPath = bootloaderPath.substring(bootloaderPath.indexOf(':') + 1);
      }
      
      File bootloadersFile = new File(t.getFolder(), "bootloaders");
      File bootloaderFile = new File(bootloadersFile, bootloaderPath);
      bootloaderPath = bootloaderFile.getAbsolutePath();
      
      bootloader.add("-Uflash:w:" + bootloaderPath + File.separator +
                     boardPreferences.get("bootloader.file") + ":i");
    }
    if (boardPreferences.get("bootloader.lock_bits") != null)
      bootloader.add("-Ulock:w:" + boardPreferences.get("bootloader.lock_bits") + ":m");

    if (bootloader.size() > 0)
      return avrdude(params, bootloader);
    
    return true;
  }
  
  public boolean avrdude(Collection p1, Collection p2) throws RunnerException {
    ArrayList p = new ArrayList(p1);
    p.addAll(p2);
    return avrdude(p);
  }
  
  public boolean avrdude(Collection params) throws RunnerException {
    List commandDownloader = new ArrayList();
      
    if(Base.isLinux()) {
      if ((new File(Base.getHardwarePath() + "/tools/" + "avrdude")).exists()) {
        commandDownloader.add(Base.getHardwarePath() + "/tools/" + "avrdude");
        commandDownloader.add("-C" + Base.getHardwarePath() + "/tools/avrdude.conf");
      } else {
        commandDownloader.add("avrdude");
      }
    }
    else {
      commandDownloader.add(Base.getHardwarePath() + "/tools/avr/bin/" + "avrdude");
      commandDownloader.add("-C" + Base.getHardwarePath() + "/tools/avr/etc/avrdude.conf");
    }

    if (verbose || Preferences.getBoolean("upload.verbose")) {
      commandDownloader.add("-v");
      commandDownloader.add("-v");
      commandDownloader.add("-v");
      commandDownloader.add("-v");
    } else {
      commandDownloader.add("-q");
      commandDownloader.add("-q");
    }
    commandDownloader.add("-p" + Base.getBoardPreferences().get("build.mcu"));
    commandDownloader.addAll(params);

    return executeUploadCommand(commandDownloader);
  }
}
