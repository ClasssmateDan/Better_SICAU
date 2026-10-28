using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace KeJianDesktop {
    internal sealed class PortableDataResult {
        internal string Root;
        internal bool Migrated;
        internal string Warning;
    }

    internal static class PortableData {
        internal const string BundleMarker = "发布资源清单.sha256";
        private sealed class BundleAssets {
            internal string Root;
            internal List<string> Directories;
            internal Dictionary<string,string> Hashes;
        }
        private static BundleAssets ReadBundleAssets(string folder) {
            string marker=Path.Combine(folder,BundleMarker);
            if(!File.Exists(marker)) return null;
            try {
                if(new FileInfo(marker).Length>16000) return null;
                List<string> directories=new List<string>();
                List<string> files=FilesIn(folder,folder,directories);
                string[] lines=File.ReadAllLines(marker,Encoding.UTF8);
                if(lines.Length<2 || lines[0]!="KeJian bundled assets v1") return null;
                Dictionary<string,string> hashes=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
                HashSet<string> allowedDirectories=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach(string line in lines.Skip(1)) {
                    if(line.Length==0) continue;
                    string[] parts=line.Split('\t');
                    if(parts.Length!=2 || !Regex.IsMatch(parts[0],@"\A[0-9a-f]{64}\z")) return null;
                    string relative=parts[1];
                    bool allowed=new [] { "manifest.json", "background.js", "connect.js", "har_format.js", "help.html" }.Any(name=>relative=="course-tools/browser-extension/"+name)
                        || relative=="course-tools/课程工具使用说明.md" || relative=="course-tools/课程工具第三方许可.txt"
                        || Regex.IsMatch(relative,@"\Aruntime/[0-9]+\.[0-9]+\.[0-9]+/course-tools/KeJianCourseTools\.exe\z");
                    if(!allowed) return null;
                    string path=Path.Combine(folder,relative.Replace('/',Path.DirectorySeparatorChar)); ChildOf(folder,path);
                    string local=path.Substring(folder.Length+1);
                    if(hashes.ContainsKey(local) || !File.Exists(path) || DigestHex(path)!=parts[0]) return null;
                    hashes.Add(local,Digest(path));
                    string parent=Path.GetDirectoryName(local);
                    while(!String.IsNullOrEmpty(parent)) { allowedDirectories.Add(parent); parent=Path.GetDirectoryName(parent); }
                }
                if(hashes.Count==0 || !new HashSet<string>(files,StringComparer.OrdinalIgnoreCase).SetEquals(hashes.Keys.Concat(new [] {BundleMarker})) || directories.Any(name=>!allowedDirectories.Contains(name))) return null;
                hashes.Add(BundleMarker,Digest(marker));
                return new BundleAssets {Root=folder,Directories=directories,Hashes=hashes};
            } catch(IOException) { return null; } catch(UnauthorizedAccessException) { return null; }
        }
        private static string DigestHex(string filename) {
            using(SHA256 sha=SHA256.Create())
            using(FileStream file=File.OpenRead(filename)) return BitConverter.ToString(sha.ComputeHash(file)).Replace("-","").ToLowerInvariant();
        }
        private static void RestoreBundleAssets(string app,BundleAssets assets,string target) {
            ChildOf(app,assets.Root); ChildOf(app,target);
            List<string> directories=new List<string>();
            List<string> files=FilesIn(assets.Root,assets.Root,directories);
            if(!new HashSet<string>(files,StringComparer.OrdinalIgnoreCase).SetEquals(assets.Hashes.Keys) || !new HashSet<string>(directories,StringComparer.OrdinalIgnoreCase).SetEquals(assets.Directories)) throw new IOException("发布资源暂存目录发生变化。");
            foreach(string relative in files) if(Digest(Path.Combine(assets.Root,relative))!=assets.Hashes[relative]) throw new IOException("发布资源校验失败。");
            foreach(string relative in files.Where(name=>name!=BundleMarker)) {
                string from=Path.Combine(assets.Root,relative),to=Path.Combine(target,relative); ChildOf(target,to);
                if(!File.Exists(to)) { Directory.CreateDirectory(Path.GetDirectoryName(to)); File.Copy(from,to,false); }
            }
            // Remove only the verified bundled files; unknown files are never recursively deleted.
            foreach(string relative in files) File.Delete(Path.Combine(assets.Root,relative));
            foreach(string relative in directories.OrderByDescending(name=>name.Length)) Directory.Delete(Path.Combine(assets.Root,relative),false);
            Directory.Delete(assets.Root,false);
        }
        internal static string Normalize(string value) { string full=Path.GetFullPath(value); return full.Length==Path.GetPathRoot(full).Length ? full : full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar); }
        internal static string DefaultRoot(string applicationDirectory) { return Path.Combine(Normalize(applicationDirectory), "课间数据"); }
        private static void ChildOf(string parent, string child) {
            string prefix = Normalize(parent).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
            if (!Normalize(child).StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) throw new IOException("数据路径不在预期目录中。");
        }
        private static void NoLink(string path) {
            if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw new IOException("旧数据含有目录链接，请先将实际数据放入程序旁的“课间数据”。");
        }
        private static List<string> FilesIn(string root, string current, List<string> directories) {
            if (!String.Equals(Normalize(root), Normalize(current), StringComparison.OrdinalIgnoreCase)) ChildOf(root, current);
            NoLink(current);
            List<string> files = new List<string>();
            foreach (string item in Directory.GetFileSystemEntries(current)) {
                ChildOf(root, item); NoLink(item);
                if (Directory.Exists(item)) { directories.Add(item.Substring(root.Length + 1)); files.AddRange(FilesIn(root, item, directories)); }
                else files.Add(item.Substring(root.Length + 1));
            }
            return files;
        }
        private static string Digest(string filename) {
            using (SHA256 sha = SHA256.Create())
            using (FileStream file = new FileStream(filename, FileMode.Open, FileAccess.Read, FileShare.Read)) return Convert.ToBase64String(sha.ComputeHash(file));
        }
        internal static string LegacyMutexName(string path, bool tool) {
            using (SHA256 sha = SHA256.Create()) {
                string identity = BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(Normalize(path).ToLowerInvariant()))).Replace("-", "");
                return "Local\\" + (tool ? "KeJianCourseTools-" + identity.ToLowerInvariant() : "KeJianDesktop-" + identity);
            }
        }
        private sealed class LegacyLease : IDisposable {
            private readonly List<Mutex> held = new List<Mutex>();
            internal LegacyLease(string source) {
                try {
                    foreach(string name in new [] { LegacyMutexName(source,false), LegacyMutexName(Path.Combine(source,"course-tools"),true) }) {
                        bool created;
                        Mutex mutex=new Mutex(true,name,out created);
                        if(!created) { mutex.Dispose(); throw new IOException("请先从托盘退出旧版课间，并关闭旧课程工具，再重新打开新版迁移数据。"); }
                        held.Add(mutex);
                    }
                } catch { Dispose(); throw; }
            }
            public void Dispose() { foreach(Mutex mutex in held) { mutex.ReleaseMutex(); mutex.Dispose(); } held.Clear(); }
        }
        internal static void EnsureWritable(string folder) {
            string probe = Path.Combine(folder, ".kejian-write-" + Guid.NewGuid().ToString("N") + ".tmp");
            ChildOf(folder, probe);
            try {
                using (FileStream file = new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.DeleteOnClose)) { file.WriteByte(0); file.Flush(); }
            } catch (IOException error) { throw new IOException("程序目录无法写入，请将整个程序文件夹移到可写位置后重试。不会改用 AppData。", error); }
            catch (UnauthorizedAccessException error) { throw new IOException("程序目录无法写入，请将整个程序文件夹移到可写位置后重试。不会改用 AppData。", error); }
        }
        internal static PortableDataResult Prepare(string applicationDirectory, string legacyDirectory, bool migrateLegacy, bool forceCopyForTest = false) {
            string app = Normalize(applicationDirectory), target = DefaultRoot(app), source = Normalize(legacyDirectory);
            ChildOf(app, target);
            if (Path.GetFileName(source) != "KeJianTimetable" || source.StartsWith(target + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) || target.StartsWith(source + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) || String.Equals(source,target,StringComparison.OrdinalIgnoreCase))
                throw new IOException("旧数据路径不正确，未执行迁移。");
            EnsureWritable(app);
            PortableDataResult result = new PortableDataResult { Root = target, Warning = "" };
            if (File.Exists(target)) throw new IOException("程序旁已有名为“课间数据”的文件，请换用一个空文件夹解压程序。");
            if (Directory.Exists(target)) NoLink(target);
            bool populated = Directory.Exists(target) && Directory.EnumerateFileSystemEntries(target).Any();
            BundleAssets bundle=populated ? ReadBundleAssets(target) : null;
            if(bundle!=null) populated=false;
            if (!populated && migrateLegacy && Directory.Exists(source)) {
              using (LegacyLease lease=new LegacyLease(source)) {
                List<string> directories = new List<string>();
                List<string> files = FilesIn(source, source, directories);
                if(bundle!=null) {
                    string backup=Path.Combine(app,".kejian-bundle-"+Guid.NewGuid().ToString("N")); ChildOf(app,backup);
                    Directory.Move(target,backup); bundle.Root=backup;
                }
                try {
                if (Directory.Exists(target)) { ChildOf(app,target); Directory.Delete(target, false); }
                bool sameVolume = String.Equals(Path.GetPathRoot(source), Path.GetPathRoot(target), StringComparison.OrdinalIgnoreCase);
                if (sameVolume && !forceCopyForTest) {
                    try { ChildOf(app,target); Directory.Move(source,target); }
                    catch (IOException error) { throw new IOException("旧数据仍被占用或无法移动。请退出旧版及课程工具后重试，原数据未被覆盖。",error); }
                    result.Migrated = true;
                } else {
                    CopyMigration(app,source,target,directories,files,result);
                }
                } catch {
                    if(bundle!=null && !Directory.Exists(target) && Directory.Exists(bundle.Root)) { ChildOf(app,bundle.Root); Directory.Move(bundle.Root,target); }
                    throw;
                }
                if(bundle!=null) {
                    try { RestoreBundleAssets(app,bundle,target); }
                    catch(IOException) { result.Warning+=" 发布资源保留于程序目录中的 "+Path.GetFileName(bundle.Root)+"，可重新解压发布包补齐资源。"; }
                    catch(UnauthorizedAccessException) { result.Warning+=" 发布资源暂时被占用，可重新解压发布包补齐资源。"; }
                }
              }
            }
            Directory.CreateDirectory(target); EnsureWritable(target);
            string marker = Path.Combine(target,"便携目录说明.txt");
            if (!File.Exists(marker)) File.WriteAllText(marker,"课间课程表便携数据目录\r\n课表、提醒、浏览器配置、课程工具和扩展都位于此目录。\r\n移动或备份时，请带上 EXE 和整个“课间数据”文件夹。\r\n",new UTF8Encoding(false));
            return result;
        }
        private static void CopyMigration(string app,string source,string target,List<string> directories,List<string> files,PortableDataResult result) {
            string stage = Path.Combine(app,".kejian-migrate-"+Guid.NewGuid().ToString("N"));
            ChildOf(app,stage);
            if (Directory.Exists(stage) || File.Exists(stage)) throw new IOException("迁移临时目录已存在。");
            Directory.CreateDirectory(stage);
            Dictionary<string,string> hashes = new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
            bool published = false;
            try {
                foreach (string relative in directories) { string output=Path.Combine(stage,relative); ChildOf(stage,output); Directory.CreateDirectory(output); }
                foreach (string relative in files) {
                    string input=Path.Combine(source,relative), output=Path.Combine(stage,relative);
                    ChildOf(source,input); ChildOf(stage,output);
                    using (FileStream from=new FileStream(input,FileMode.Open,FileAccess.Read,FileShare.Read))
                    using (FileStream to=new FileStream(output,FileMode.CreateNew,FileAccess.Write,FileShare.None)) from.CopyTo(to);
                    string hash=Digest(input);
                    if (hash!=Digest(output)) throw new IOException("迁移校验未通过，原数据仍保留。");
                    hashes.Add(relative,hash);
                    File.SetLastWriteTimeUtc(output,File.GetLastWriteTimeUtc(input));
                }
                // Recheck the complete source before publishing or removing any old files.
                List<string> checkedDirectories = new List<string>();
                List<string> checkedFiles=FilesIn(source,source,checkedDirectories);
                if (!new HashSet<string>(files,StringComparer.OrdinalIgnoreCase).SetEquals(checkedFiles) || !new HashSet<string>(directories,StringComparer.OrdinalIgnoreCase).SetEquals(checkedDirectories)) throw new IOException("旧目录在迁移期间发生变化，请退出旧版后重试。");
                foreach (string relative in files) if(hashes[relative]!=Digest(Path.Combine(source,relative))) throw new IOException("旧文件在迁移期间发生变化，原数据仍保留。");
                ChildOf(app,target); Directory.Move(stage,target); published=true; result.Migrated=true;
                try {
                    // The exact named legacy root and every descendant were checked above;
                    // all file bytes have a verified copy in the published target.
                    if (Path.GetFileName(source)!="KeJianTimetable") throw new IOException("旧目录校验失败。");
                    Directory.Delete(source,true);
                } catch (IOException) { result.Warning="数据已迁移到程序旁的“课间数据”，新版只使用新目录。旧位置仍有被占用文件，已保留旧副本；请退出旧版后自行核对清理。"; }
                catch (UnauthorizedAccessException) { result.Warning="数据已迁移到程序旁的“课间数据”，新版只使用新目录。旧位置无法清理，已保留旧副本，请自行核对。"; }
            } finally {
                if (!published && Directory.Exists(stage)) { ChildOf(app,stage); Directory.Delete(stage,true); }
            }
        }
    }
}
