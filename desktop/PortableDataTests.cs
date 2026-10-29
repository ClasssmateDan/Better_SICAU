// Synthetic filesystem fixtures only. All paths are explicitly under the supplied workspace folder.
using System;
using System.IO;
using System.Threading;
using System.Security.Cryptography;
using System.Text;
using KeJianDesktop;

internal static class PortableDataTests {
    private static string root;
    private static int passed;
    private static void Check(bool condition,string error) { if(!condition)throw new Exception(error); }
    private static string Case(string name) { string path=Path.GetFullPath(Path.Combine(root,name)); Check(path.StartsWith(root+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase),"fixture escapes root"); Directory.CreateDirectory(path); return path; }
    private static void Put(string path,string value) { Directory.CreateDirectory(Path.GetDirectoryName(path)); File.WriteAllText(path,value); }
    private static void Throws(Action operation) { bool failed=false; try{operation();}catch(IOException){failed=true;} Check(failed,"expected migration to stop"); }
    private static void Run(string name,Action action) { action(); passed++; Console.WriteLine("PASS "+name); }
    private static string Bundle(string app) {
        string folder=PortableData.DefaultRoot(app);
        string[] names={"course-tools/browser-extension/manifest.json","course-tools/browser-extension/background.js","runtime/1.7.4/course-tools/KeJianCourseTools.exe"};
        StringBuilder manifest=new StringBuilder("KeJian bundled assets v1\n");
        foreach(string name in names) {
            string file=Path.Combine(folder,name); Put(file,"bundled "+name);
            using(SHA256 hash=SHA256.Create()) manifest.Append(BitConverter.ToString(hash.ComputeHash(File.ReadAllBytes(file))).Replace("-","").ToLowerInvariant()).Append('\t').Append(name).Append('\n');
        }
        Put(Path.Combine(folder,PortableData.BundleMarker),manifest.ToString()); return folder;
    }
    private static int Main(string[] args) {
        try {
            root=Path.GetFullPath(args[0]).TrimEnd(Path.DirectorySeparatorChar); Directory.CreateDirectory(root);
            Run("unpacked bundled assets do not block either migration path",delegate {
                foreach(bool copy in new [] {false,true}) {
                    string tag="bundle-"+copy,app=Case(tag+"/app"),old=Path.Combine(Case(tag+"/legacy"),"KeJianTimetable");
                    string target=Bundle(app); Put(Path.Combine(old,"timetable.json"),"personal timetable");
                    PortableDataResult result=PortableData.Prepare(app,old,true,copy);
                    Check(result.Migrated&&result.Warning.Length==0&&!Directory.Exists(old),"bundle blocked migration");
                    Check(File.ReadAllText(Path.Combine(target,"timetable.json"))=="personal timetable"&&File.Exists(Path.Combine(target,"course-tools/browser-extension/manifest.json"))&&File.Exists(Path.Combine(target,"runtime/1.7.4/course-tools/KeJianCourseTools.exe")),"migration lost data or packaged assets");
                    Check(Directory.GetDirectories(app,".kejian-bundle-*").Length==0,"bundle staging leaked");
                }
            });
            Run("fresh bundle without legacy initializes once and keeps later data",delegate {
                string app=Case("bundle-new/app"),old=Path.Combine(Case("bundle-new/legacy"),"KeJianTimetable"),target=Bundle(app);
                Check(!PortableData.Prepare(app,old,true).Migrated,"unexpected migration");
                Put(Path.Combine(target,"timetable.json"),"current timetable"); Put(Path.Combine(old,"timetable.json"),"old timetable");
                Check(!PortableData.Prepare(app,old,true).Migrated&&File.ReadAllText(Path.Combine(target,"timetable.json"))=="current timetable","initialized portable data overwritten");
            });
            Run("changed assets or additional personal files prevent bundle replacement",delegate {
                foreach(bool changed in new [] {false,true}) {
                    string tag="bundle-edited-"+changed,app=Case(tag+"/app"),old=Path.Combine(Case(tag+"/legacy"),"KeJianTimetable"),target=Bundle(app);
                    string edited=Path.Combine(target,changed ? "course-tools/browser-extension/background.js" : "timetable.json"); Put(edited,"keep edited file"); Put(Path.Combine(old,"timetable.json"),"old");
                    Check(!PortableData.Prepare(app,old,true).Migrated&&File.ReadAllText(edited)=="keep edited file"&&Directory.Exists(old),"edited portable files overwritten");
                }
            });
            Run("busy legacy leaves unpacked extension available",delegate {
                string app=Case("bundle-busy/app"),old=Path.Combine(Case("bundle-busy/legacy"),"KeJianTimetable"),target=Bundle(app); Put(Path.Combine(old,"timetable.json"),"keep");
                using(Mutex mutex=new Mutex(false,PortableData.LegacyMutexName(old,false))) Throws(delegate {PortableData.Prepare(app,old,true);});
                Check(File.Exists(Path.Combine(target,PortableData.BundleMarker))&&File.Exists(Path.Combine(target,"course-tools/browser-extension/manifest.json")),"busy migration changed unpacked bundle");
            });
            Run("failed old directory move restores unpacked bundle",delegate {
                string app=Case("bundle-locked/app"),old=Path.Combine(Case("bundle-locked/legacy"),"KeJianTimetable"),target=Bundle(app),file=Path.Combine(old,"timetable.json"); Put(file,"keep locked");
                using(FileStream held=new FileStream(file,FileMode.Open,FileAccess.Read,FileShare.Read)) Throws(delegate {PortableData.Prepare(app,old,true);});
                Check(File.Exists(Path.Combine(target,PortableData.BundleMarker))&&File.Exists(file)&&Directory.GetDirectories(app,".kejian-bundle-*").Length==0,"failed migration did not restore bundle");
            });
            Run("new data stays next to EXE",delegate {
                string app=Case("new/app"),old=Path.Combine(Case("new/legacy"),"KeJianTimetable");
                PortableDataResult result=PortableData.Prepare(app,old,true);
                Check(PortableData.DefaultRoot(Path.GetPathRoot(app))==Path.Combine(Path.GetPathRoot(app),"课间数据"),"drive root became relative");
                Check(result.Root==Path.Combine(app,"课间数据")&&!result.Migrated,"wrong portable location");
                Check(!Directory.Exists(old)&&File.Exists(Path.Combine(result.Root,"便携目录说明.txt")),"legacy folder was created");
            });
            Run("whole old tree moves including browser and tool data",delegate {
                string app=Case("move/app"),old=Path.Combine(Case("move/legacy"),"KeJianTimetable");
                Put(Path.Combine(old,"timetable.json"),"synthetic timetable"); Put(Path.Combine(old,"settings.json"),"synthetic settings");
                Put(Path.Combine(old,"WebView2","Default","profile.dat"),"synthetic browser");
                Put(Path.Combine(old,"course-tools","records","example.har"),"synthetic HAR"); Put(Path.Combine(old,"course-tools","browser-extension","manifest.json"),"synthetic extension");
                PortableDataResult result=PortableData.Prepare(app,old,true);
                Check(result.Migrated&&!Directory.Exists(old),"old data was not moved");
                Check(File.ReadAllText(Path.Combine(result.Root,"timetable.json"))=="synthetic timetable","timetable changed");
                Check(File.ReadAllText(Path.Combine(result.Root,"WebView2","Default","profile.dat"))=="synthetic browser","profile lost");
                Check(File.ReadAllText(Path.Combine(result.Root,"course-tools","records","example.har"))=="synthetic HAR","tool records lost");
            });
            Run("verified cross-volume copy removes old tree",delegate {
                string app=Case("copy/app"),old=Path.Combine(Case("copy/legacy"),"KeJianTimetable");
                Put(Path.Combine(old,"timetable.json"),"copy timetable"); Directory.CreateDirectory(Path.Combine(old,"empty"));
                PortableDataResult result=PortableData.Prepare(app,old,true,true);
                Check(result.Migrated&&!Directory.Exists(old)&&result.Warning=="","copy migration failed");
                Check(Directory.Exists(Path.Combine(result.Root,"empty"))&&File.ReadAllText(Path.Combine(result.Root,"timetable.json"))=="copy timetable","copied tree changed");
            });
            Run("active old application is left intact",delegate {
                string app=Case("busy/app"),old=Path.Combine(Case("busy/legacy"),"KeJianTimetable"); Put(Path.Combine(old,"timetable.json"),"keep");
                using(Mutex mutex=new Mutex(false,PortableData.LegacyMutexName(old,false))) Throws(delegate { PortableData.Prepare(app,old,true); });
                Check(File.ReadAllText(Path.Combine(old,"timetable.json"))=="keep"&&!Directory.Exists(PortableData.DefaultRoot(app)),"busy source changed");
            });
            Run("active old course tool is also detected",delegate {
                string app=Case("tool-busy/app"),old=Path.Combine(Case("tool-busy/legacy"),"KeJianTimetable"); Put(Path.Combine(old,"timetable.json"),"keep");
                using(Mutex mutex=new Mutex(false,PortableData.LegacyMutexName(Path.Combine(old,"course-tools"),true))) Throws(delegate { PortableData.Prepare(app,old,true); });
                Check(Directory.Exists(old),"active tool source changed");
            });
            Run("existing portable data is never overwritten",delegate {
                string app=Case("existing/app"),old=Path.Combine(Case("existing/legacy"),"KeJianTimetable");
                Put(Path.Combine(old,"timetable.json"),"old"); Put(Path.Combine(PortableData.DefaultRoot(app),"timetable.json"),"portable");
                PortableDataResult result=PortableData.Prepare(app,old,true);
                Check(!result.Migrated&&File.ReadAllText(Path.Combine(result.Root,"timetable.json"))=="portable"&&Directory.Exists(old),"portable preferences overwritten");
            });
            Run("blocked portable destination has no AppData fallback",delegate {
                string app=Case("blocked/app"),old=Path.Combine(Case("blocked/legacy"),"KeJianTimetable"); Put(Path.Combine(old,"timetable.json"),"keep");
                File.WriteAllText(PortableData.DefaultRoot(app),"destination file"); Throws(delegate { PortableData.Prepare(app,old,true); });
                Check(File.ReadAllText(PortableData.DefaultRoot(app))=="destination file"&&File.Exists(Path.Combine(old,"timetable.json")),"blocked migration changed data");
            });
            Run("overlapping or wrong legacy roots are rejected",delegate {
                string old=Path.Combine(Case("overlap"),"KeJianTimetable"); Directory.CreateDirectory(old); string app=Path.Combine(old,"app"); Directory.CreateDirectory(app);
                Throws(delegate { PortableData.Prepare(app,old,true); });
                Throws(delegate { PortableData.Prepare(Case("wrong/app"),Case("wrong/not-legacy"),true); });
            });
            Run("locked cleanup preserves verified portable copy",delegate {
                string app=Case("locked/app"),old=Path.Combine(Case("locked/legacy"),"KeJianTimetable"); string file=Path.Combine(old,"timetable.json"); Put(file,"verified keep");
                using(FileStream held=new FileStream(file,FileMode.Open,FileAccess.Read,FileShare.Read)) {
                    PortableDataResult result=PortableData.Prepare(app,old,true,true);
                    Check(result.Migrated&&result.Warning.Length>0&&File.ReadAllText(Path.Combine(result.Root,"timetable.json"))=="verified keep"&&File.Exists(file),"locked copy not retained");
                }
            });
            Console.WriteLine("Portable migration checks passed: "+passed); return 0;
        } catch(Exception error) { Console.Error.WriteLine(error); return 1; }
    }
}
