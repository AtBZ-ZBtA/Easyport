package easyport.tools;

import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
import java.io.*; import java.nio.file.*; import java.util.*; import java.util.zip.*;
/**
 * Promotes a candidate rename to a real one only when the two types declare the same surface.
 *
 * The rules files allow a TYPE_RENAME on two grounds: the loader dispatches by the type, or the
 * whole surface is verified identical. The first is answered by reading the class's kind; this
 * answers the second, and answers it mechanically rather than one launch at a time.
 *
 * <h2>Why it matters that this is not "the names match"</h2>
 *
 * Renaming on a name match is the mistake the forward direction measured: 118 rules that resolved
 * to a type without the member the corpus called on it, each one a NoSuchMethodError waiting for
 * the right code path. So both types are read out of their platform jars and their public and
 * protected members compared -- names and descriptors -- with the two loaders' package prefixes
 * collapsed, so a method returning IItemHandler matches the one returning IItemHandler.
 *
 * The rejections carry as much information as the promotions. IEventBus differs 13 members to 14,
 * which is precisely why it has a hand-written shim.
 *
 * Run:
 *   java -cp "&lt;asm&gt;;&lt;asm-tree&gt;" tools/SurfaceMatch.java &lt;candidates.tsv&gt; &lt;platform jars...&gt;
 *
 * Input is TYPE_RENAME lines; output is the same file with `.identical` and `.differ` suffixes,
 * the first ready to paste into a rules file and the second annotated with why each was refused.
 */
public class SurfaceMatch {
  static Map<String,ClassNode> nodes = new HashMap<>();
  public static void main(String[] a) throws Exception {
    for (int i = 1; i < a.length; i++) index(Paths.get(a[i]));
    List<String> same = new ArrayList<>(), differ = new ArrayList<>();
    for (String line : Files.readAllLines(Paths.get(a[0]))) {
      String[] f = line.split("\t");
      if (f.length < 3) continue;
      ClassNode neo = nodes.get(f[1]), forge = nodes.get(f[2]);
      if (neo == null || forge == null) { differ.add(line + "\t#unindexed"); continue; }
      boolean neoItf = (neo.access & Opcodes.ACC_INTERFACE) != 0;
      boolean forgeItf = (forge.access & Opcodes.ACC_INTERFACE) != 0;
      if (neoItf != forgeItf) { differ.add(line + "\t#kind-differs"); continue; }
      Set<String> ns = surface(neo), fs = surface(forge);
      if (ns.isEmpty()) { differ.add(line + "\t#no-members"); continue; }
      if (ns.equals(fs)) same.add(f[0] + "\t" + f[1] + "\t" + f[2] + "\t#" + ns.size() + "-members");
      else differ.add(line + "\t#surface-differs(" + ns.size() + " vs " + fs.size() + ")");
    }
    Files.write(Paths.get(a[0] + ".identical"), same);
    Files.write(Paths.get(a[0] + ".differ"), differ);
    System.out.println("identical surface " + same.size() + ", differing " + differ.size());
  }
  /** Public members, with both loaders' package prefixes collapsed so descriptors compare. */
  static Set<String> surface(ClassNode c) {
    Set<String> out = new TreeSet<>();
    for (MethodNode m : c.methods) {
      if ((m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) continue;
      if (m.name.equals("<clinit>")) continue;
      out.add(m.name + norm(m.desc));
    }
    for (FieldNode f : c.fields) {
      if ((f.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) continue;
      out.add(f.name + " " + norm(f.desc));
    }
    return out;
  }
  static String norm(String d) {
    return d.replace("net/neoforged/neoforge/", "LOADER/")
            .replace("net/neoforged/neoforgespi/", "LOADER/")
            .replace("net/neoforged/fml/", "LOADER/fml/")
            .replace("net/neoforged/bus/api/", "LOADER/bus/")
            .replace("net/neoforged/api/distmarker/", "LOADER/dist/")
            .replace("net/minecraftforge/eventbus/api/", "LOADER/bus/")
            .replace("net/minecraftforge/api/distmarker/", "LOADER/dist/")
            .replace("net/minecraftforge/fml/", "LOADER/fml/")
            .replace("net/minecraftforge/forgespi/", "LOADER/")
            .replace("net/minecraftforge/", "LOADER/");
  }
  static void index(Path jar) throws IOException {
    try (ZipFile z = new ZipFile(jar.toFile())) {
      var en = z.entries();
      while (en.hasMoreElements()) { ZipEntry e = en.nextElement();
        if (!e.getName().endsWith(".class")) continue;
        try (InputStream in = z.getInputStream(e)) {
          ClassNode cn = new ClassNode();
          new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_CODE);
          nodes.put(cn.name, cn);
        } catch (Exception ignored) {}
      }
    }
  }
}
