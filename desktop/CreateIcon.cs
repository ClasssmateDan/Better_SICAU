using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.IO.Compression;

internal static class CreateIcon {
    private static void Main(string[] args) {
        if (args[0] == "--zip" || args[0] == "--zip-root") {
            bool relative = args[0] == "--zip-root";
            string root = relative ? Path.GetFullPath(args[2]).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar : null;
            using (FileStream file = File.Create(args[1]))
            using (ZipArchive archive = new ZipArchive(file, ZipArchiveMode.Create)) {
                for (int i = relative ? 3 : 2; i < args.Length; i++) {
                    string filename = relative ? Path.GetFullPath(Path.Combine(root, args[i])) : args[i];
                    if (relative && (!filename.StartsWith(root, System.StringComparison.OrdinalIgnoreCase) || Path.IsPathRooted(args[i]) || args[i].Contains(".."))) throw new IOException("发布文件路径不正确。");
                    if (relative) {
                        string check = filename.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                        while (check.Length >= root.Length) {
                            if ((File.GetAttributes(check) & FileAttributes.ReparsePoint) != 0) throw new IOException("发布文件不能是目录链接。");
                            check = Path.GetDirectoryName(check);
                        }
                    }
                    string name = relative ? args[i].Replace('\\', '/') : Path.GetFileName(args[i]);
                    ZipArchiveEntry entry = archive.CreateEntry(name, CompressionLevel.Optimal);
                    if (name.EndsWith("/")) continue;
                    using (Stream output = entry.Open())
                    using (Stream input = File.OpenRead(filename)) input.CopyTo(output);
                }
            }
            return;
        }
        using (Bitmap bitmap = new Bitmap(128, 128))
        using (Graphics graphics = Graphics.FromImage(bitmap))
        using (GraphicsPath path = new GraphicsPath())
        using (SolidBrush brush = new SolidBrush(ColorTranslator.FromHtml("#e87e64")))
        using (Pen pen = new Pen(Color.White, 6))
        using (MemoryStream png = new MemoryStream()) {
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.Clear(Color.Transparent);
            path.AddArc(4, 4, 40, 40, 180, 90); path.AddArc(84, 4, 40, 40, 270, 90);
            path.AddArc(84, 84, 40, 40, 0, 90); path.AddArc(4, 84, 40, 40, 90, 90); path.CloseFigure();
            graphics.FillPath(brush, path);
            graphics.DrawRectangle(pen, 35, 35, 58, 62); graphics.DrawLine(pen, 35, 56, 93, 56);
            graphics.DrawLine(pen, 48, 26, 48, 45); graphics.DrawLine(pen, 79, 26, 79, 45);
            graphics.DrawLine(pen, 48, 71, 58, 71); graphics.DrawLine(pen, 70, 71, 80, 71); graphics.DrawLine(pen, 48, 84, 58, 84);
            bitmap.Save(png, ImageFormat.Png);
            byte[] bytes = png.ToArray();
            using (BinaryWriter output = new BinaryWriter(File.Create(args[0]))) {
                output.Write((ushort)0); output.Write((ushort)1); output.Write((ushort)1);
                output.Write((byte)128); output.Write((byte)128); output.Write((byte)0); output.Write((byte)0);
                output.Write((ushort)1); output.Write((ushort)32); output.Write((uint)bytes.Length); output.Write((uint)22); output.Write(bytes);
            }
        }
    }
}
