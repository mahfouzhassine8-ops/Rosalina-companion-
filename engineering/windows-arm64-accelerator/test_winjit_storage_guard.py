#!/usr/bin/env python3
"""Compile and execute the staged Java guard against disposable files only."""
from pathlib import Path
import subprocess
import sys
import tempfile

source = Path(sys.argv[1])/'qube-android-lib/src/main/java/com/max2idea/android/qube/main/WindowsWinjitMode.java'
getters = ['HdaImagePath','HdbImagePath','HdcImagePath','HddImagePath','CdImagePath',
           'FdaImagePath','FdbImagePath','UsbImagePath','Kernel','InitRd','Bios',
           'BiosCode','BiosVars','BiosType','ExtraParams','SharedFolderPath']
with tempfile.TemporaryDirectory(prefix='winjit-storage-guard-') as d:
    root = Path(d)
    def write(path, body):
        p=root/path
        p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(body)
    write('android/content/Context.java', '''package android.content;
import java.io.File;
public class Context {
    public static final int MODE_PRIVATE=0;
    public File base; public boolean optIn=false;
    public Context(File base) {this.base=base;}
    public File getExternalFilesDir(String type) {return base;}
    public Pref getSharedPreferences(String p,int m) {return new Pref();}
    public class Pref {
        public boolean getBoolean(String k,boolean def) {return optIn;}
        public Pref edit(){return this;}
        public Pref putBoolean(String k,boolean v){optIn=v;return this;}
        public void apply(){}
    }
}''')
    write('com/max2idea/android/qube/machine/Machine.java',
          'package com.max2idea.android.qube.machine;\npublic class Machine {\n'+
          'public int getEnableKVM(){return 0;}\n'+
          '\n'.join(f'public String {n}=""; public String get{n}(){{return {n};}}' for n in getters)+'\n}')
    write('com/max2idea/android/qube/main/WindowsCpuMode.java',
          'package com.max2idea.android.qube.main; public class WindowsCpuMode {'+
          'public static boolean isTargetWindowsVM(android.content.Context c,com.max2idea.android.qube.machine.Machine m){return true;}}')
    write('com/max2idea/android/qube/main/WindowsWinjitMode.java',source.read_text())
    write('GuardTest.java', '''import android.content.Context;
import com.max2idea.android.qube.machine.Machine;
import com.max2idea.android.qube.main.WindowsWinjitMode;
import java.nio.file.*;
public class GuardTest {
    static void check(boolean v,String msg){if(!v) throw new AssertionError(msg);}
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory("winjit-guard-files-");
        try {
            Path sandbox=Files.createDirectory(base.resolve("WinJIT-disposable"));
            Path disk=Files.createFile(sandbox.resolve("Windows-test.qcow2"));
            Path vars=Files.createFile(sandbox.resolve("VARS.fd"));
            Path live=Files.createFile(base.resolve("Windows-installed.qcow2"));
            Context c=new Context(base.toFile()); Machine m=new Machine();
            m.HdaImagePath=disk.toString();m.BiosType="UEFI";m.BiosVars=vars.toString();m.BiosCode="Default";
            check(WindowsWinjitMode.hasDisposableStorage(c,m),"disposable disk + separate VARS accepted");
            check(!WindowsWinjitMode.shouldUse(c,m),"TCG default");
            WindowsWinjitMode.setEnabled(c,true);
            check(WindowsWinjitMode.shouldUse(c,m),"explicit opt-in for sandbox VM");
            m.HdaImagePath=live.toString();
            check(!WindowsWinjitMode.shouldUse(c,m),"original installed disk rejected despite opt-in");
            m.HdaImagePath=disk.toString();m.BiosVars="Default";
            check(!WindowsWinjitMode.shouldUse(c,m),"shared default UEFI VARS rejected");
            m.BiosVars=vars.toString();m.ExtraParams="-drive file="+live;
            check(!WindowsWinjitMode.shouldUse(c,m),"extra arguments rejected");
            m.ExtraParams="";m.SharedFolderPath=base.toString();
            check(!WindowsWinjitMode.shouldUse(c,m),"shared folder rejected");
            m.SharedFolderPath="";m.HdbImagePath=live.toString();
            check(!WindowsWinjitMode.shouldUse(c,m),"secondary disk outside sandbox rejected");
            m.HdbImagePath="";m.HdaImagePath="content://provider/Windows-test.qcow2";
            check(!WindowsWinjitMode.shouldUse(c,m),"ambiguous content URI rejected");
            Path link=Files.createSymbolicLink(sandbox.resolve("Windows-link.qcow2"),live);
            m.HdaImagePath=link.toString();
            check(!WindowsWinjitMode.shouldUse(c,m),"symlink escape rejected");
            m.HdaImagePath=sandbox.resolve("../Windows-installed.qcow2").toString();
            check(!WindowsWinjitMode.shouldUse(c,m),"parent traversal rejected");
            m.HdaImagePath=disk.toString();WindowsWinjitMode.setEnabled(c,false);
            check(!WindowsWinjitMode.shouldUse(c,m),"reversible TCG restore");
            System.out.println("PASS: actual Java storage guard, opt-in/default, disk/VARS, media, arguments and path escapes");
        } finally {
            try (var paths=Files.walk(base)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});
            }
        }
    }
}''')
    subprocess.run(['javac','-d',str(root/'classes')]+[str(p) for p in root.rglob('*.java')],check=True)
    subprocess.run(['java','-cp',str(root/'classes'),'GuardTest'],check=True)
