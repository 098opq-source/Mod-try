package dev.worldrestart;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Setzt eine Welt zurück, ohne ihre Einstellungen zu verlieren:
 * - löscht alle Weltdaten (Chunks, Dimensionen, Spielerdaten, Statistiken ...)
 * - setzt in der level.dat einen neuen Seed und entfernt Spieler-/Spawn-/Wetterdaten
 * Alles andere (Gamerules, Schwierigkeit, Hardcore, Welttyp, Datapacks ...) bleibt erhalten.
 * Reines Java, keine Minecraft-Klassen nötig.
 */
final class LevelDatEditor {
    private LevelDatEditor() {}

    /** Diese Dateien/Ordner bleiben beim Löschen erhalten. */
    private static final Set<String> KEEP = Set.of(
            "level.dat", "level.dat_old", "session.lock", "datapacks", "icon.png");

    /** Diese Tags werden aus level.dat -> Data entfernt (Weltzustand). */
    private static final String[] REMOVE_FROM_DATA = {
            "Player", "DragonFight", "CustomBossEvents", "ScheduledEvents",
            "WanderingTraderId", "WanderingTraderSpawnChance", "WanderingTraderSpawnDelay",
            "spawn", "SpawnX", "SpawnY", "SpawnZ", "SpawnAngle",
            "raining", "thundering", "rainTime", "thunderTime", "clearWeatherTime"
    };

    // ---------------------------------------------------------------- Ordner leeren

    static void wipeWorldFolder(Path worldDir) throws IOException {
        if (!Files.isRegularFile(worldDir.resolve("level.dat"))) {
            throw new IOException("Kein level.dat in " + worldDir + " - breche ab.");
        }
        List<Path> children;
        try (Stream<Path> s = Files.list(worldDir)) {
            children = s.toList();
        }
        for (Path child : children) {
            if (KEEP.contains(child.getFileName().toString())) continue;
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ---------------------------------------------------------------- level.dat bearbeiten

    private record Tag(byte type, Object value) {}

    private record ListVal(byte elemType, List<Object> items) {}

    @SuppressWarnings("unchecked")
    static void applyNewSeed(Path levelDat, long newSeed) throws IOException {
        String rootName;
        Map<String, Tag> root;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(levelDat))))) {
            if (in.readByte() != 10) throw new IOException("level.dat: Wurzel ist kein Compound");
            rootName = in.readUTF();
            root = readCompound(in);
        }

        Tag dataTag = root.get("Data");
        if (dataTag == null || dataTag.type() != 10) throw new IOException("level.dat: 'Data' fehlt");
        Map<String, Tag> data = (Map<String, Tag>) dataTag.value();

        Tag wgsTag = data.get("WorldGenSettings");
        if (wgsTag == null || wgsTag.type() != 10) {
            throw new IOException("level.dat: 'WorldGenSettings' fehlt (unbekanntes Format)");
        }
        Map<String, Tag> wgs = (Map<String, Tag>) wgsTag.value();
        wgs.put("seed", new Tag((byte) 4, newSeed));

        for (String key : REMOVE_FROM_DATA) data.remove(key);
        if (data.containsKey("Time")) data.put("Time", new Tag((byte) 4, 0L));
        if (data.containsKey("DayTime")) data.put("DayTime", new Tag((byte) 4, 0L));
        // Spawnpunkt wird beim Laden neu gesucht
        data.put("initialized", new Tag((byte) 1, (byte) 0));

        Path tmp = levelDat.resolveSibling("level.dat.tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeByte(10);
            out.writeUTF(rootName);
            writeCompound(out, root);
        }
        Files.move(tmp, levelDat, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------------------------------------------------------------- minimaler NBT-Reader/Writer

    private static Map<String, Tag> readCompound(DataInputStream in) throws IOException {
        Map<String, Tag> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == 0) return map;
            String name = in.readUTF();
            map.put(name, new Tag(type, readPayload(in, type)));
        }
    }

    private static Object readPayload(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] arr = new byte[in.readInt()];
                in.readFully(arr);
                return arr;
            }
            case 8: return in.readUTF();
            case 9: {
                byte elem = in.readByte();
                int len = in.readInt();
                List<Object> items = new ArrayList<>(Math.max(len, 0));
                for (int i = 0; i < len; i++) items.add(readPayload(in, elem));
                return new ListVal(elem, items);
            }
            case 10: return readCompound(in);
            case 11: {
                int[] arr = new int[in.readInt()];
                for (int i = 0; i < arr.length; i++) arr[i] = in.readInt();
                return arr;
            }
            case 12: {
                long[] arr = new long[in.readInt()];
                for (int i = 0; i < arr.length; i++) arr[i] = in.readLong();
                return arr;
            }
            default: throw new IOException("Unbekannter NBT-Typ " + type);
        }
    }

    private static void writeCompound(DataOutputStream out, Map<String, Tag> map) throws IOException {
        for (Map.Entry<String, Tag> e : map.entrySet()) {
            out.writeByte(e.getValue().type());
            out.writeUTF(e.getKey());
            writePayload(out, e.getValue().type(), e.getValue().value());
        }
        out.writeByte(0);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, byte type, Object v) throws IOException {
        switch (type) {
            case 1: out.writeByte((Byte) v); break;
            case 2: out.writeShort((Short) v); break;
            case 3: out.writeInt((Integer) v); break;
            case 4: out.writeLong((Long) v); break;
            case 5: out.writeFloat((Float) v); break;
            case 6: out.writeDouble((Double) v); break;
            case 7: {
                byte[] arr = (byte[]) v;
                out.writeInt(arr.length);
                out.write(arr);
                break;
            }
            case 8: out.writeUTF((String) v); break;
            case 9: {
                ListVal list = (ListVal) v;
                out.writeByte(list.elemType());
                out.writeInt(list.items().size());
                for (Object item : list.items()) writePayload(out, list.elemType(), item);
                break;
            }
            case 10: writeCompound(out, (Map<String, Tag>) v); break;
            case 11: {
                int[] arr = (int[]) v;
                out.writeInt(arr.length);
                for (int x : arr) out.writeInt(x);
                break;
            }
            case 12: {
                long[] arr = (long[]) v;
                out.writeInt(arr.length);
                for (long x : arr) out.writeLong(x);
                break;
            }
            default: throw new IOException("Unbekannter NBT-Typ " + type);
        }
    }
}
