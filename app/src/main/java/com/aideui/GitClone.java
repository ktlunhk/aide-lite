package com.aideui;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

/**
 * Small, dependency-free Git smart-HTTP clone for public HTTPS repositories.
 * Writes real SHA-1 Git objects, branch refs, index and a working tree.
 * Deliberately limited to normal non-shallow SHA-1 repositories; no SSH/auth,
 * sparse checkout, LFS fetching or automatic submodule cloning.
 */
public final class GitClone {
    private GitClone() { }
    private static final int MAX_PACK = 120 * 1024 * 1024;
    private static final int MAX_OBJECT = 48 * 1024 * 1024;
    private static final long MAX_UNPACKED = 320L * 1024 * 1024;
    private static final int MAX_OBJECTS = 80000;
    private static final int MAX_FILES = 70000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public interface Progress {
        void update(String phase);
        boolean cancelled();
    }

    static void progress(Progress p, String message) throws IOException {
        if (p != null) {
            if (p.cancelled()) throw new IOException("Clone cancelled");
            p.update(message);
        }
    }

    static class RemoteRefs {
        final Map<String,String> refs = new HashMap<String,String>();
        final Set<String> capabilities = new HashSet<String>();
        String symref = null, head = null;
        String branch() throws IOException {
            if (symref != null && symref.startsWith("refs/heads/")) return symref.substring(11);
            if (head != null) {
                for (Map.Entry<String,String> e : refs.entrySet())
                    if (e.getKey().startsWith("refs/heads/") && head.equals(e.getValue()))
                        return e.getKey().substring(11);
            }
            if (refs.containsKey("refs/heads/main")) return "main";
            if (refs.containsKey("refs/heads/master")) return "master";
            for (String ref : refs.keySet()) if (ref.startsWith("refs/heads/")) return ref.substring(11);
            throw new IOException("No branch was advertised by the remote repository");
        }
        String headCommit(String branch) throws IOException {
            String s = refs.get("refs/heads/" + branch);
            if (s == null) s = head;
            if (!hexSha(s)) throw new IOException("Remote did not advertise a valid SHA-1 commit");
            return s;
        }
    }

    /** Uses a public repository URL, e.g. https://github.com/owner/repo.git. */
    public static void cloneRepository(String remoteUrl, File destination, Progress progress) throws Exception {
        String remote = normalizeUrl(remoteUrl);
        if (destination == null) throw new IOException("Choose a destination folder");
        if (destination.exists()) throw new IOException("Destination already exists: " + destination.getName());
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canWrite())
            throw new IOException("Destination parent is not writable");
        progress(progress, "Connecting to Git server...");
        RemoteRefs refs = requestRefs(remote);
        String branch = refs.branch();
        String commit = refs.headCommit(branch);
        progress(progress, "Downloading " + branch + " branch...");
        byte[] pack = requestPack(remote, commit, refs.capabilities, progress);
        progress(progress, "Checking Git objects...");
        cloneFromPack(destination, remote, branch, commit, pack, progress);
    }

    static String normalizeUrl(String raw) throws IOException {
        String url = raw == null ? "" : raw.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        URL parsed;
        try { parsed = new URL(url); }
        catch (Exception e) { throw new IOException("Enter a valid HTTPS Git repository URL"); }
        if (!"https".equalsIgnoreCase(parsed.getProtocol()) || parsed.getUserInfo() != null
                || parsed.getHost().length() == 0 || parsed.getQuery() != null || parsed.getRef() != null)
            throw new IOException("Use a public HTTPS repository URL without passwords or URL parameters");
        if (url.contains("\\") || url.contains("..") || parsed.getPath().length() < 2)
            throw new IOException("Invalid Git repository path");
        return url;
    }

    static HttpURLConnection open(String location, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(location).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "AideUI-Git/1.0");
        conn.setRequestProperty("Pragma", "no-cache");
        return conn;
    }

    static HttpURLConnection connected(String url, String method, byte[] request) throws IOException {
        String next = url;
        for (int redirects = 0; redirects < 4; redirects++) {
            HttpURLConnection conn = open(next, method);
            if (request == null) {
                conn.setRequestProperty("Accept", "application/x-git-upload-pack-advertisement");
            } else {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-git-upload-pack-request");
                conn.setRequestProperty("Accept", "application/x-git-upload-pack-result");
                conn.setFixedLengthStreamingMode(request.length);
                OutputStream out = conn.getOutputStream();
                try { out.write(request); } finally { out.close(); }
            }
            int status = conn.getResponseCode();
            if (status >= 300 && status <= 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("Git server redirected without a URL");
                URL redirected = new URL(new URL(next), location);
                if (!"https".equalsIgnoreCase(redirected.getProtocol()))
                    throw new IOException("Refusing non-HTTPS Git redirect");
                next = redirected.toExternalForm();
                continue;
            }
            if (status != 200) {
                conn.disconnect();
                if (status == 401 || status == 403) throw new IOException("Private repository or access denied (HTTP " + status + ")");
                if (status == 404) throw new IOException("Repository not found (HTTP 404)");
                throw new IOException("Git server returned HTTP " + status);
            }
            return conn;
        }
        throw new IOException("Too many Git HTTP redirects");
    }

    static class Packet {
        final byte[] data;
        Packet(byte[] data) { this.data = data; }
        String string() { return new String(data, UTF8); }
    }

    static boolean readFullAllowEof(InputStream stream, byte[] buf, int offset, int count) throws IOException {
        int soFar = 0;
        while (soFar < count) {
            int got = stream.read(buf, offset + soFar, count - soFar);
            if (got < 0) {
                if (soFar == 0) return false;
                throw new IOException("Truncated Git packet header");
            }
            if (got == 0) continue;
            soFar += got;
        }
        return true;
    }

    static void readFull(InputStream stream, byte[] buf, int offset, int count) throws IOException {
        if (!readFullAllowEof(stream, buf, offset, count)) throw new IOException("Truncated Git packet");
    }

    static Packet nextPacket(InputStream stream) throws IOException {
        byte[] n = new byte[4];
        if (!readFullAllowEof(stream, n, 0, 4)) return null;
        int size;
        try { size = Integer.parseInt(new String(n, UTF8), 16); }
        catch (NumberFormatException e) { throw new IOException("Malformed Git packet length"); }
        if (size == 0) return new Packet(null);
        if (size < 4 || size > 65520) throw new IOException("Invalid Git packet length " + size);
        byte[] data = new byte[size - 4];
        readFull(stream, data, 0, data.length);
        return new Packet(data);
    }

    static byte[] pkt(String s) {
        byte[] content = s.getBytes(UTF8);
        byte[] prefix = String.format(Locale.US, "%04x", content.length + 4).getBytes(UTF8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(prefix, 0, prefix.length);
        buf.write(content, 0, content.length);
        return buf.toByteArray();
    }

    static RemoteRefs requestRefs(String remote) throws Exception {
        HttpURLConnection conn = connected(remote + "/info/refs?service=git-upload-pack", "GET", null);
        try {
            InputStream in = new BufferedInputStream(conn.getInputStream());
            RemoteRefs refs = new RemoteRefs();
            Packet first = nextPacket(in);
            if (first == null || first.data == null || !first.string().startsWith("# service=git-upload-pack"))
                throw new IOException("Server does not advertise Git smart HTTP");
            Packet flush = nextPacket(in);
            if (flush == null || flush.data != null) throw new IOException("Invalid Git service advertisement");
            boolean firstRef = true;
            Packet packet;
            while ((packet = nextPacket(in)) != null && packet.data != null) {
                String line = packet.string();
                int nul = line.indexOf('\0');
                if (nul >= 0) {
                    String capabilityString = line.substring(nul + 1).trim();
                    for (String cap : capabilityString.split(" ")) if (cap.length() > 0) {
                        refs.capabilities.add(cap);
                        if (cap.startsWith("symref=HEAD:")) refs.symref = cap.substring("symref=HEAD:".length());
                    }
                    line = line.substring(0, nul);
                }
                if (line.length() < 42 || line.charAt(40) != ' ') continue;
                String oid = line.substring(0, 40);
                String ref = line.substring(41).trim();
                if (!hexSha(oid)) continue;
                if (ref.equals("HEAD")) refs.head = oid;
                refs.refs.put(ref, oid);
                firstRef = false;
            }
            if (firstRef) throw new IOException("Repository has no commits to clone");
            return refs;
        } finally { conn.disconnect(); }
    }

    static boolean hexSha(String s) { return s != null && s.matches("[0-9a-fA-F]{40}"); }

    static byte[] requestPack(String remote, String commit, Set<String> caps, Progress progress) throws Exception {
        if (!caps.contains("side-band-64k") && !caps.contains("side-band"))
            throw new IOException("Git server does not support side-band transfer");
        String cap = caps.contains("side-band-64k") ? "side-band-64k" : "side-band";
        if (caps.contains("ofs-delta")) cap += " ofs-delta";
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(pkt("want " + commit + " " + cap + "\n"));
        request.write("0000".getBytes(UTF8));
        request.write(pkt("done\n"));
        HttpURLConnection conn = connected(remote + "/git-upload-pack", "POST", request.toByteArray());
        try {
            InputStream stream = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            Packet p;
            while ((p = nextPacket(stream)) != null) {
                if (p.data == null) break;
                if (p.data.length == 0) continue;
                String s = p.string();
                if (s.equals("NAK\n") || s.startsWith("ACK ") || s.startsWith("shallow ")) continue;
                int channel = p.data[0] & 255;
                if (channel == 1) {
                    int count = p.data.length - 1;
                    if (pack.size() + count > MAX_PACK) throw new IOException("Git repository exceeds the 120 MB clone limit");
                    pack.write(p.data, 1, count);
                    if (pack.size() % (2 * 1024 * 1024) < 65536) progress(progress, "Received " + (pack.size() / 1024 / 1024) + " MB...");
                } else if (channel == 2) {
                    String msg = new String(p.data, 1, p.data.length - 1, UTF8).trim();
                    if (msg.length() > 0) progress(progress, msg.length() > 90 ? msg.substring(0, 90) : msg);
                } else if (channel == 3) {
                    throw new IOException("Git server: " + new String(p.data, 1, p.data.length - 1, UTF8).trim());
                } else throw new IOException("Unexpected Git upload-pack response");
            }
            byte[] bytes = pack.toByteArray();
            if (bytes.length < 32) throw new IOException("Git server returned an empty pack");
            return bytes;
        } finally { conn.disconnect(); }
    }

    static class Obj {
        int type;
        byte[] raw;
        String hash;
        long offset;
        long parentOffset;
        String parentHash;
        Obj(int type, byte[] body, long off) { this.type = type; raw = body; offset = off; }
    }

    static class Reader {
        final byte[] input;
        int pos;
        Reader(byte[] bytes) { input = bytes; }
        int u8() throws IOException { if (pos >= input.length) throw new IOException("Truncated Git pack"); return input[pos++] & 255; }
        byte[] bytes(int len) throws IOException {
            if (len < 0 || input.length - pos < len) throw new IOException("Truncated Git pack");
            byte[] part = Arrays.copyOfRange(input, pos, pos + len);
            pos += len; return part;
        }
        long big32() throws IOException {
            return ((long)u8() << 24) | ((long)u8() << 16) | ((long)u8() << 8) | u8();
        }
        byte[] inflate(int expected) throws IOException {
            if (expected < 0 || expected > MAX_OBJECT) throw new IOException("Git object exceeds 48 MB limit");
            Inflater inflate = new Inflater();
            inflate.setInput(input, pos, input.length - pos);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(expected, 8192));
            byte[] buf = new byte[16384];
            try {
                while (!inflate.finished()) {
                    int count;
                    try { count = inflate.inflate(buf); }
                    catch (DataFormatException e) { throw new IOException("Invalid compressed Git object", e); }
                    if (count == 0) {
                        if (inflate.needsInput() || inflate.needsDictionary()) throw new IOException("Truncated Git compressed data");
                        throw new IOException("Git decompression stalled");
                    }
                    if (out.size() + count > MAX_OBJECT) throw new IOException("Git object too large");
                    out.write(buf, 0, count);
                }
                pos = input.length - inflate.getRemaining();
                byte[] result = out.toByteArray();
                if (result.length != expected) throw new IOException("Git object size mismatch");
                return result;
            } finally { inflate.end(); }
        }
    }

    static String typeName(int type) throws IOException {
        switch (type) {
            case 1: return "commit";
            case 2: return "tree";
            case 3: return "blob";
            case 4: return "tag";
            default: throw new IOException("Unsupported Git object type " + type);
        }
    }

    static byte[] content(Obj o) throws IOException {
        byte[] header = (typeName(o.type) + " " + o.raw.length + "\0").getBytes(UTF8);
        ByteArrayOutputStream result = new ByteArrayOutputStream(header.length + o.raw.length);
        result.write(header, 0, header.length);
        result.write(o.raw, 0, o.raw.length);
        return result.toByteArray();
    }

    static String hex(byte[] raw) {
        final char[] h = "0123456789abcdef".toCharArray();
        char[] dest = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            dest[i * 2] = h[(raw[i] >>> 4) & 15];
            dest[i * 2 + 1] = h[raw[i] & 15];
        }
        return new String(dest);
    }

    static String sha(byte[] input) throws IOException {
        try { return hex(MessageDigest.getInstance("SHA-1").digest(input)); }
        catch (Exception e) { throw new IOException("SHA-1 unavailable", e); }
    }

    static List<Obj> readPack(byte[] pack, Progress progress) throws IOException {
        if (pack.length < 32) throw new IOException("Git pack too short");
        String checksum = sha(Arrays.copyOf(pack, pack.length - 20));
        if (!checksum.equals(hex(Arrays.copyOfRange(pack, pack.length - 20, pack.length))))
            throw new IOException("Git pack checksum mismatch");
        Reader r = new Reader(pack);
        if (!Arrays.equals(r.bytes(4), "PACK".getBytes(UTF8))) throw new IOException("Missing Git PACK header");
        long version = r.big32();
        if (version != 2 && version != 3) throw new IOException("Unsupported Git pack version " + version);
        long count = r.big32();
        if (count > MAX_OBJECTS) throw new IOException("Git pack exceeds object count limit");
        List<Obj> objects = new ArrayList<Obj>();
        Map<Long,Obj> offsets = new HashMap<Long,Obj>();
        Map<String,Obj> known = new HashMap<String,Obj>();
        long total = 0;
        for (long i = 0; i < count; i++) {
            if (i % 128 == 0) progress(progress, "Unpacking " + i + "/" + count + " Git objects...");
            long offset = r.pos;
            int b = r.u8();
            int type = (b >> 4) & 7;
            long size = b & 15;
            int shift = 4;
            while ((b & 128) != 0) {
                b = r.u8();
                size |= (long)(b & 127) << shift;
                shift += 7;
                if (shift > 63) throw new IOException("Invalid Git object header");
            }
            if (size > MAX_OBJECT) throw new IOException("Git object too large");
            Obj obj = new Obj(type, null, offset);
            if (type == 6) {
                int c = r.u8();
                long distance = c & 127;
                while ((c & 128) != 0) {
                    c = r.u8();
                    distance = ((distance + 1) << 7) | (c & 127);
                }
                obj.parentOffset = offset - distance;
            } else if (type == 7) obj.parentHash = hex(r.bytes(20));
            else if (type < 1 || type > 4) throw new IOException("Unsupported Git packed object type " + type);
            obj.raw = r.inflate((int)size);
            total += obj.raw.length;
            if (total > MAX_UNPACKED) throw new IOException("Unpacked repository exceeds size limit");
            if (type >= 1 && type <= 4) {
                obj.hash = sha(content(obj));
                known.put(obj.hash, obj);
            }
            objects.add(obj);
            offsets.put(offset, obj);
        }
        if (r.pos != pack.length - 20) throw new IOException("Extra data in Git pack");
        int unresolved = 0;
        for (Obj obj : objects) if (obj.hash == null) unresolved++;
        while (unresolved > 0) {
            int progressThisPass = 0;
            for (Obj obj : objects) {
                if (obj.hash != null) continue;
                Obj base = obj.type == 6 ? offsets.get(obj.parentOffset) : known.get(obj.parentHash);
                if (base == null || base.hash == null) continue;
                obj.raw = applyDelta(base.raw, obj.raw);
                obj.type = base.type;
                obj.hash = sha(content(obj));
                known.put(obj.hash, obj);
                progressThisPass++;
                unresolved--;
            }
            if (progressThisPass == 0) throw new IOException("Cannot resolve Git pack deltas (thin pack or invalid pack)");
        }
        return objects;
    }

    static long varSize(byte[] delta, int[] pos) throws IOException {
        long value = 0;
        int shift = 0;
        int c;
        do {
            if (pos[0] >= delta.length || shift > 56) throw new IOException("Invalid Git delta varint");
            c = delta[pos[0]++] & 255;
            value |= (long)(c & 127) << shift;
            shift += 7;
        } while ((c & 128) != 0);
        return value;
    }

    static byte[] applyDelta(byte[] base, byte[] delta) throws IOException {
        int[] index = {0};
        if (varSize(delta, index) != base.length) throw new IOException("Git delta base size mismatch");
        long targetSize = varSize(delta, index);
        if (targetSize > MAX_OBJECT) throw new IOException("Delta target exceeds object size limit");
        ByteArrayOutputStream result = new ByteArrayOutputStream((int) targetSize);
        while (index[0] < delta.length) {
            int command = delta[index[0]++] & 255;
            if ((command & 128) != 0) {
                int offset = 0, length = 0;
                for (int i = 0; i < 4; i++) if ((command & (1 << i)) != 0) {
                    if (index[0] >= delta.length) throw new IOException("Truncated Git delta copy");
                    offset |= (delta[index[0]++] & 255) << (i * 8);
                }
                for (int i = 0; i < 3; i++) if ((command & (1 << (i + 4))) != 0) {
                    if (index[0] >= delta.length) throw new IOException("Truncated Git delta copy size");
                    length |= (delta[index[0]++] & 255) << (i * 8);
                }
                if (length == 0) length = 65536;
                if (offset < 0 || length < 0 || offset > base.length - length || result.size() + length > targetSize)
                    throw new IOException("Git delta copy is out of range");
                result.write(base, offset, length);
            } else if (command != 0) {
                if (index[0] + command > delta.length || result.size() + command > targetSize)
                    throw new IOException("Git delta insertion is out of range");
                result.write(delta, index[0], command);
                index[0] += command;
            } else throw new IOException("Invalid Git delta instruction");
        }
        if (result.size() != targetSize) throw new IOException("Git delta output size mismatch");
        return result.toByteArray();
    }

    static void mkdir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
    }

    static void write(File file, byte[] content) throws IOException {
        mkdir(file.getParentFile());
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(content); } finally { out.close(); }
    }

    static String cleanName(String name) throws IOException {
        if (name.length() == 0 || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0 || name.equalsIgnoreCase(".git"))
            throw new IOException("Unsafe repository filename: " + name);
        return name;
    }

    static class IndexEntry {
        String path, hash;
        int mode;
        long size;
    }

    static class Checkout {
        File root;
        Map<String,Obj> objects;
        List<IndexEntry> index = new ArrayList<IndexEntry>();
        int files = 0;
        Progress progress;

        void tree(String treeSha, String prefix, int depth) throws IOException {
            if (depth > 80) throw new IOException("Git tree nesting too deep");
            Obj obj = objects.get(treeSha);
            if (obj == null || obj.type != 2) throw new IOException("Missing Git tree: " + treeSha);
            byte[] bytes = obj.raw;
            int i = 0;
            while (i < bytes.length) {
                int space = i;
                while (i < bytes.length && bytes[i] != ' ') i++;
                if (i >= bytes.length) throw new IOException("Invalid Git tree mode");
                String textMode = new String(bytes, space, i - space, UTF8);
                int mode;
                try { mode = Integer.parseInt(textMode, 8); }
                catch (Exception e) { throw new IOException("Invalid Git tree mode: " + textMode); }
                int begin = ++i;
                while (i < bytes.length && bytes[i] != 0) i++;
                if (i == bytes.length || i + 20 >= bytes.length) throw new IOException("Truncated Git tree");
                String name = cleanName(new String(bytes, begin, i - begin, UTF8));
                String path = prefix + name;
                i++;
                byte[] id = Arrays.copyOfRange(bytes, i, i + 20);
                i += 20;
                String hash = hex(id);
                File dest = new File(root, path);
                if ((mode & 0170000) == 0040000) {
                    mkdir(dest);
                    tree(hash, path + "/", depth + 1);
                } else if ((mode & 0170000) == 0120000) {
                    throw new IOException("Repository contains symbolic links, unsupported on shared Android storage: " + path);
                } else if ((mode & 0170000) == 0160000) {
                    mkdir(dest);
                    IndexEntry entry = new IndexEntry();
                    entry.path = path; entry.hash = hash; entry.mode = mode; entry.size = 0;
                    index.add(entry);
                } else if ((mode & 0170000) == 0100000) {
                    Obj file = objects.get(hash);
                    if (file == null || file.type != 3) throw new IOException("Missing Git blob " + hash);
                    write(dest, file.raw);
                    files++;
                    if (files > MAX_FILES) throw new IOException("Too many files to check out");
                    if (files % 150 == 0) progress(progress, "Writing working files: " + files);
                    IndexEntry entry = new IndexEntry();
                    entry.path = path; entry.hash = hash; entry.mode = mode; entry.size = file.raw.length;
                    index.add(entry);
                } else throw new IOException("Unsupported repository file mode " + textMode);
            }
        }
    }

    static void writeIndex(File dotGit, List<IndexEntry> entries) throws IOException {
        Collections.sort(entries, new Comparator<IndexEntry>() {
            public int compare(IndexEntry a, IndexEntry b) { return a.path.compareTo(b.path); }
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write("DIRC".getBytes(UTF8));
        out.writeInt(2);
        out.writeInt(entries.size());
        for (IndexEntry entry : entries) {
            int begin = bytes.size();
            out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(0);
            out.writeInt(0); out.writeInt(0); out.writeInt(entry.mode);
            out.writeInt(0); out.writeInt(0);
            out.writeInt((int)Math.min(Integer.MAX_VALUE, entry.size));
            out.write(hexToBytes(entry.hash));
            byte[] name = entry.path.getBytes(UTF8);
            out.writeShort(Math.min(0xfff, name.length));
            out.write(name);
            out.writeByte(0);
            while ((bytes.size() - begin) % 8 != 0) out.writeByte(0);
        }
        out.flush();
        byte[] body = bytes.toByteArray();
        out.write(hexToBytes(sha(body)));
        out.flush();
        write(new File(dotGit, "index"), bytes.toByteArray());
    }

    static byte[] hexToBytes(String hash) throws IOException {
        if (!hexSha(hash)) throw new IOException("Invalid object hash");
        byte[] bytes = new byte[20];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    static String commitTree(Obj commit) throws IOException {
        if (commit == null || commit.type != 1) throw new IOException("Git HEAD is not a commit");
        String text = new String(commit.raw, UTF8);
        if (!text.startsWith("tree ") || text.length() < 45) throw new IOException("Missing Git commit tree");
        String tree = text.substring(5, 45);
        if (!hexSha(tree)) throw new IOException("Invalid Git tree id");
        return tree;
    }

    static void saveObject(File dotGit, Obj obj) throws IOException {
        File location = new File(dotGit, "objects/" + obj.hash.substring(0, 2) + "/" + obj.hash.substring(2));
        if (location.isFile()) return;
        mkdir(location.getParentFile());
        FileOutputStream disk = new FileOutputStream(location);
        DeflaterOutputStream zlib = new DeflaterOutputStream(disk);
        try { zlib.write(content(obj)); } finally { zlib.close(); }
    }

    /** Testable pack checkout entrypoint; does not require a network connection. */
    static void cloneFromPack(File destination, String remote, String branch, String commitId, byte[] pack, Progress progress) throws Exception {
        if (destination.exists()) throw new IOException("Destination already exists: " + destination);
        if (!branch.matches("[A-Za-z0-9_./-]+") || branch.contains("..") || branch.startsWith("/") || branch.endsWith("/") || branch.contains("//") || branch.endsWith(".lock"))
            throw new IOException("Invalid remote branch name");
        List<Obj> packObjects = readPack(pack, progress);
        Map<String,Obj> objects = new HashMap<String,Obj>();
        for (Obj o : packObjects) objects.put(o.hash, o);
        Obj commit = objects.get(commitId.toLowerCase(Locale.US));
        String tree = commitTree(commit);
        if (!destination.mkdir()) throw new IOException("Cannot create project directory");
        try {
            File dotGit = new File(destination, ".git");
            mkdir(new File(dotGit, "objects"));
            progress(progress, "Saving Git objects...");
            int n = 0;
            for (Obj o : packObjects) {
                if (n++ % 128 == 0) progress(progress, "Saving Git object " + n + "/" + packObjects.size());
                saveObject(dotGit, o);
            }
            progress(progress, "Checking out branch " + branch + "...");
            Checkout checkout = new Checkout();
            checkout.root = destination;
            checkout.objects = objects;
            checkout.progress = progress;
            checkout.tree(tree, "", 0);
            writeIndex(dotGit, checkout.index);
            write(new File(dotGit, "HEAD"), ("ref: refs/heads/" + branch + "\n").getBytes(UTF8));
            write(new File(dotGit, "refs/heads/" + branch), (commitId + "\n").getBytes(UTF8));
            write(new File(dotGit, "refs/remotes/origin/" + branch), (commitId + "\n").getBytes(UTF8));
            String safeRemote = normalizeUrl(remote);
            String config = "[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n"
                + "\tlogallrefupdates = true\n"
                + "[remote \"origin\"]\n\turl = " + safeRemote + "\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n"
                + "[branch \"" + branch + "\"]\n\tremote = origin\n\tmerge = refs/heads/" + branch + "\n";
            write(new File(dotGit, "config"), config.getBytes(UTF8));
            write(new File(dotGit, "description"), "AideUI cloned Git project\n".getBytes(UTF8));
            progress(progress, "Completed: " + checkout.files + " files on " + branch);
        } catch (Exception e) {
            // This directory was freshly created above. Never touch an existing user folder.
            removeNewDirectory(destination);
            throw e;
        }
    }

    static void removeNewDirectory(File dir) {
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) removeNewDirectory(child);
            else child.delete();
        }
        dir.delete();
    }
}
