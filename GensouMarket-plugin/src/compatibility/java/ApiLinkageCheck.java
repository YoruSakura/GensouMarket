import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks the baseline bytecode against each isolated API without loading server classes. */
public final class ApiLinkageCheck {
    private record Reference(int kind, String owner, String name, String descriptor) {}
    private record ClassInfo(int access, String parent, List<String> interfaces,
                             Set<String> fields, Set<String> methods, Set<Reference> references,
                             Set<String> types) {}

    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]);
        Set<Reference> references = new HashSet<>();
        Set<String> types = new HashSet<>();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                try (var input = Files.newInputStream(file)) {
                    ClassInfo info = read(input);
                    references.addAll(info.references());
                    types.addAll(info.types());
                }
            }
        }
        for (int i = 1; i < args.length; i += 2) {
            String label = args[i];
            List<URL> urls = new ArrayList<>();
            urls.add(classes.toUri().toURL());
            for (String entry : args[i + 1].split(Pattern.quote(java.io.File.pathSeparator))) {
                urls.add(Path.of(entry).toUri().toURL());
            }
            try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                var checker = new Checker(loader);
                List<String> errors = new ArrayList<>();
                for (String type : types) {
                    if (relevant(type) && checker.find(type) == null) errors.add("Missing class " + type);
                }
                for (Reference ref : references) {
                    if (!relevant(ref.owner())) continue;
                    ClassInfo owner = checker.find(ref.owner());
                    if (owner == null || !checker.hasMember(ref.owner(), ref, new HashSet<>())) {
                        errors.add("Missing member " + ref.owner() + "." + ref.name() + ref.descriptor());
                    } else if (ref.kind() != 9 && (ref.kind() == 11) != ((owner.access() & 0x0200) != 0)) {
                        errors.add("Class/interface changed for " + ref.owner());
                    }
                }
                if (!errors.isEmpty()) {
                    errors.stream().sorted().distinct().forEach(e -> System.err.println(label + ": " + e));
                    throw new IllegalStateException("Baseline bytecode does not link against " + label);
                }
                System.out.println(label + ": baseline bytecode member references OK");
            }
        }
    }

    private static boolean relevant(String name) {
        return name.startsWith("org/bukkit/") || name.startsWith("io/papermc/")
                || name.startsWith("com/destroystokyo/") || name.startsWith("net/kyori/")
                || name.startsWith("net/scarletphantasy/gensouMarket/");
    }

    private static final class Checker {
        private final ClassLoader loader;
        private final Map<String, ClassInfo> cache = new HashMap<>();

        Checker(ClassLoader loader) { this.loader = loader; }

        ClassInfo find(String name) throws IOException {
            if (name == null) return null;
            if (cache.containsKey(name)) return cache.get(name);
            try (InputStream input = loader.getResourceAsStream(name + ".class")) {
                ClassInfo info = input == null ? null : read(input);
                cache.put(name, info);
                return info;
            }
        }

        boolean hasMember(String name, Reference ref, Set<String> seen) throws IOException {
            if (name == null || !seen.add(name)) return false;
            ClassInfo info = find(name);
            if (info == null) return false;
            if ((ref.kind() == 9 ? info.fields() : info.methods()).contains(ref.name() + ref.descriptor())) return true;
            if (ref.name().equals("<init>")) return false;
            if (hasMember(info.parent(), ref, seen)) return true;
            for (String face : info.interfaces()) if (hasMember(face, ref, seen)) return true;
            return false;
        }
    }

    private static ClassInfo read(InputStream input) throws IOException {
        var in = new DataInputStream(input);
        if (in.readInt() != 0xCAFEBABE) throw new IOException("Invalid class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        int[] tags = new int[count];
        Object[] pool = new Object[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); i++; }
                case 7, 8, 16, 19, 20 -> pool[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> pool[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        Set<Reference> refs = new HashSet<>();
        Set<String> types = new HashSet<>();
        for (int i = 1; i < count; i++) {
            if (tags[i] == 7) types.add(className(pool, i));
            if (tags[i] < 9 || tags[i] > 11) continue;
            int[] member = (int[]) pool[i];
            int[] nameType = (int[]) pool[member[1]];
            refs.add(new Reference(tags[i], className(pool, member[0]),
                    (String) pool[nameType[0]], (String) pool[nameType[1]]));
        }
        int access = in.readUnsignedShort();
        in.readUnsignedShort();
        String parent = className(pool, in.readUnsignedShort());
        List<String> interfaces = new ArrayList<>();
        int faces = in.readUnsignedShort();
        for (int i = 0; i < faces; i++) interfaces.add(className(pool, in.readUnsignedShort()));
        Set<String> fields = readMembers(in, pool);
        Set<String> methods = readMembers(in, pool);
        return new ClassInfo(access, parent, interfaces, fields, methods, refs, types);
    }

    private static String className(Object[] pool, int index) {
        return index == 0 ? null : (String) pool[(Integer) pool[index]];
    }

    private static Set<String> readMembers(DataInputStream in, Object[] pool) throws IOException {
        Set<String> members = new HashSet<>();
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            members.add((String) pool[in.readUnsignedShort()] + pool[in.readUnsignedShort()]);
            int attributes = in.readUnsignedShort();
            for (int j = 0; j < attributes; j++) {
                in.readUnsignedShort();
                in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
            }
        }
        return members;
    }
}
