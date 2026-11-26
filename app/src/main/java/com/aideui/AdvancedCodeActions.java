package com.aideui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java-only code actions for the AIDE-style editor. This is a conservative,
 * source-based implementation, NOT the proprietary AIDE Java resolver/AST.
 * All disk changes are returned as backup-protected previewable Plans.
 */
public final class AdvancedCodeActions {
    private AdvancedCodeActions() { }

    private static String nl(String s) { return s.contains("\r\n") ? "\r\n" : "\n"; }
    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static IOException fail(String message) { return new IOException(message); }

    public static final class Field {
        public final String name, type;
        public final boolean isStatic, isFinal, hasInitializer;
        Field(String n, String t, boolean st, boolean fin, boolean init) {
            name=n; type=t; isStatic=st; isFinal=fin; hasInitializer=init;
        }
        public String label() { return type + " " + name + (isStatic ? " (static)" : "") + (isFinal ? " (final)" : ""); }
    }
    public static final class ClassInfo {
        public final String name;
        public final int open, close;
        public final List<Field> fields;
        public final boolean androidComponent;
        ClassInfo(String n, int a, int b, List<Field> fields, boolean android) {
            name=n; open=a; close=b; this.fields=fields; androidComponent=android;
        }
    }

    private static String withoutNonCode(String s) {
        boolean[] mask = RefactorEngine.codeMask(s);
        StringBuilder b = new StringBuilder(s.length());
        for (int i=0; i<s.length(); i++) b.append(mask[i] ? s.charAt(i) : (s.charAt(i)=='\n' ? '\n' : ' '));
        return b.toString();
    }

    /** Package imports are sorted; wildcard and static imports are supported. */
    public static RefactorEngine.Plan organizeImports(File file, boolean removeUnused) throws IOException {
        if (RefactorEngine.isKotlin(file)) return organizeKotlinImports(file, removeUnused);
        String src = RefactorEngine.read(file);
        String masked = withoutNonCode(src);
        Matcher all = Pattern.compile("(?m)^[ \\t]*import[ \\t]+(static[ \\t]+)?([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$*][\\w$*]*)*)[ \\t]*;[ \\t]*(?:\\r?\\n|$)").matcher(src);
        List<String> normals = new ArrayList<String>(), statics = new ArrayList<String>();
        int first=-1, last=-1, count=0;
        while (all.find()) {
            int marker=all.start();
            while(marker<all.end() && (src.charAt(marker)==' ' || src.charAt(marker)=='\t')) marker++;
            if(marker>=masked.length() || masked.charAt(marker)!='i') continue;
            if (first < 0) first = all.start();
            else if (src.substring(last, all.start()).trim().length() != 0) {
                throw fail("Comments or code between import statements: cannot rearrange safely. Move the comments, then retry.");
            }
            String full = all.group(2);
            boolean isStatic = all.group(1) != null;
            if (!removeUnused || usedImport(masked, all, full, isStatic)) {
                if (isStatic) statics.add(full); else normals.add(full);
            }
            last=all.end(); count++;
        }
        if (count == 0) throw fail("No Java import declarations in this file.");
        Collections.sort(normals);
        Collections.sort(statics);
        normals = new ArrayList<String>(new LinkedHashSet<String>(normals));
        statics = new ArrayList<String>(new LinkedHashSet<String>(statics));
        String eol=nl(src);
        StringBuilder insert=new StringBuilder();
        for (String imp : normals) insert.append("import ").append(imp).append(';').append(eol);
        if (!statics.isEmpty() && !normals.isEmpty()) insert.append(eol);
        for (String imp : statics) insert.append("import static ").append(imp).append(';').append(eol);
        String updated = src.substring(0, first) + insert + src.substring(last);
        if (updated.equals(src)) throw fail("Imports are already organized.");
        RefactorEngine.Plan p = new RefactorEngine.Plan("Organize Imports" + (removeUnused ? ": sort and remove unused explicit imports" : ": sort only")
            + "\nWildcard imports are kept. Comments inside import blocks are not moved."
            + (removeUnused ? "\nUnused detection is lexical, not type-aware. Review the result." : ""));
        p.add(file, file, src, updated, count);
        p.openAfter=file;
        return p;
    }

    /** Kotlin imports: no semicolons, optional "as Alias", used-name check also looks inside string templates. */
    private static RefactorEngine.Plan organizeKotlinImports(File file, boolean removeUnused) throws IOException {
        String src = RefactorEngine.read(file);
        String masked = withoutNonCode(src);
        Matcher all = Pattern.compile("(?m)^[ \\t]*import[ \\t]+([A-Za-z_`][\\w`]*(?:\\.[\\w`*]+)*)(?:[ \\t]+as[ \\t]+([A-Za-z_]\\w*))?[ \\t]*;?[ \\t]*(?:\\r?\\n|$)").matcher(src);
        List<String[]> found = new ArrayList<String[]>();
        int first = -1, last = -1;
        while (all.find()) {
            int marker = all.start();
            while (marker < all.end() && (src.charAt(marker) == ' ' || src.charAt(marker) == '\t')) marker++;
            if (marker >= masked.length() || masked.charAt(marker) != 'i') continue;
            if (first < 0) first = all.start();
            else if (src.substring(last, all.start()).trim().length() != 0)
                throw fail("Comments or code between import statements: cannot rearrange safely. Move the comments, then retry.");
            found.add(new String[]{all.group(1), all.group(2)});
            last = all.end();
        }
        if (found.isEmpty()) throw fail("No Kotlin import declarations in this file.");
        String body = masked.replaceAll("(?m)^[ \\t]*import[ \\t]+[^\\n]*", "");
        List<String> lines = new ArrayList<String>();
        for (String[] imp : found) {
            String path = imp[0], alias = imp[1];
            String name = alias != null ? alias : path.substring(path.lastIndexOf('.') + 1);
            boolean keep = !removeUnused || path.endsWith(".*")
                || Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(body).find()
                || usedInTemplate(src, name);
            if (keep) lines.add("import " + path + (alias != null ? " as " + alias : ""));
        }
        Collections.sort(lines);
        List<String> unique = new ArrayList<String>(new LinkedHashSet<String>(lines));
        String eol = nl(src);
        StringBuilder insert = new StringBuilder();
        for (String line : unique) insert.append(line).append(eol);
        String updated = src.substring(0, first) + insert + src.substring(last);
        if (updated.equals(src)) throw fail("Imports are already organized.");
        RefactorEngine.Plan p = new RefactorEngine.Plan("Organize Kotlin imports" + (removeUnused ? ": sort and remove unused explicit imports" : ": sort only")
            + "\nWildcard imports and aliases are kept. Comments inside import blocks are not moved."
            + (removeUnused ? "\nUnused detection is lexical, not type-aware. Extension functions and operators used implicitly may look unused: review the result." : ""));
        p.add(file, file, src, updated, found.size());
        p.openAfter = file;
        return p;
    }

    private static boolean usedInTemplate(String src, String name) {
        Matcher m = Pattern.compile("\\$(?:\\{([^{}\\n]*)\\}|([A-Za-z_]\\w*))").matcher(src);
        Pattern token = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])");
        while (m.find()) {
            String inner = m.group(1) != null ? m.group(1) : m.group(2);
            if (token.matcher(inner).find()) return true;
        }
        return false;
    }

    private static boolean usedImport(String masked, Matcher declaration, String path, boolean isStatic) {
        if (path.endsWith(".*")) return true;
        String name = path.substring(path.lastIndexOf('.')+1);
        String body = masked.substring(0, declaration.start()) + masked.substring(declaration.end());
        // Other import declarations are NOT counted as a use.
        body = body.replaceAll("(?m)^[ \\t]*import[ \\t]+[^;]+;", "");
        Matcher tok = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(body);
        return tok.find();
    }

    /** Comment or uncomment selected lines; unlike toggle, action is explicit/idempotent. */
    public static CodeActions.SelectionEdit lineComment(String src, int a, int b, boolean comment) {
        a = Math.max(0, Math.min(a, src.length()));
        b = Math.max(a, Math.min(b, src.length()));
        int first = src.lastIndexOf('\n', Math.max(0, a-1)) + 1;
        int lastChar = b;
        if (lastChar > first && src.charAt(lastChar-1) == '\n') lastChar--;
        int end = src.indexOf('\n', lastChar);
        end = end < 0 ? src.length() : end+1;
        String section = src.substring(first, end);
        String[] lines = section.split("(?<=\\n)", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String eol = line.endsWith("\r\n") ? "\r\n" : (line.endsWith("\n") ? "\n" : "");
            String code = line.substring(0, line.length()-eol.length());
            int i=0;
            while (i<code.length() && (code.charAt(i)==' ' || code.charAt(i)=='\t')) i++;
            if (i<code.length()) {
                if (comment && !code.startsWith("//", i)) code=code.substring(0,i)+"//"+code.substring(i);
                if (!comment && code.startsWith("//", i)) code=code.substring(0,i)+code.substring(i+2);
            }
            out.append(code).append(eol);
        }
        String replacement=out.toString();
        return new CodeActions.SelectionEdit(src.substring(0,first)+replacement+src.substring(end), first, first+replacement.length());
    }

    /** Wrap a selection of whole Java statement lines with try/catch. */
    public static RefactorEngine.Plan surroundTryCatch(File file, int a, int b) throws IOException {
        String src=RefactorEngine.read(file);
        if (a<0 || b<=a || b>src.length()) throw fail("Long-press and select one or more complete Java statement lines.");
        int first=src.lastIndexOf('\n', Math.max(0,a-1))+1;
        int finalPosition=b;
        if (finalPosition>first && src.charAt(finalPosition-1)=='\n') finalPosition--;
        int lastEnd=src.indexOf('\n', finalPosition);
        if (lastEnd<0) lastEnd=src.length();
        if (!src.substring(first,a).trim().isEmpty() || !src.substring(Math.min(finalPosition,lastEnd),lastEnd).trim().isEmpty())
            throw fail("Select whole statement lines, not part of an expression.");
        String original=src.substring(first,lastEnd);
        if (original.trim().isEmpty()) throw fail("Select non-empty Java statements.");
        final boolean kotlin = RefactorEngine.isKotlin(file);
        if (kotlin ? RefactorEngine.findEnclosingFunBrace(src, first) < 0 : RefactorEngine.findEnclosingMethodBrace(src, first) < 0)
            throw fail(kotlin ? "Selection must be inside a Kotlin function." : "Selection must be inside a Java method or constructor.");
        String ind=RefactorEngine.indent(src,first);
        String eol=nl(src);
        String nested=RefactorEngine.reindent(original,ind+"    ").replace("\n",eol);
        String replacement=ind+"try {"+eol+nested+eol+ind+(kotlin ? "} catch (e: Exception) {" : "} catch (Exception e) {")+eol+
            ind+"    e.printStackTrace()"+(kotlin ? "" : ";")+eol+ind+"}";
        String updated=src.substring(0,first)+replacement+src.substring(lastEnd);
        RefactorEngine.Plan p=new RefactorEngine.Plan("Surround selected " + (kotlin ? "Kotlin" : "Java") + " statements with try/catch."
            + "\nCatches Exception and prints its stack trace. Review checked-exception handling and local-variable scope.");
        p.add(file,file,src,updated,1); p.openAfter=file; return p;
    }

    /** Locate first top-level or nested named class, plus straightforward member fields. */
    public static ClassInfo inspectClass(String src) throws IOException {
        String masked=withoutNonCode(src);
        Matcher m=Pattern.compile("\\bclass\\s+([A-Za-z_$][\\w$]*)\\b").matcher(masked);
        int open=-1; String name=null;
        while (m.find()) {
            int n=masked.indexOf('{',m.end());
            if (n<0 || masked.substring(m.end(),n).indexOf(';')>=0) continue;
            name=m.group(1); open=n; break;
        }
        if (name==null) throw fail("No ordinary Java class found (interfaces/records are unsupported).");
        int close=RefactorEngine.matchingBrace(src,open);
        if (close<0) throw fail("Class braces are not balanced.");
        String header=masked.substring(Math.max(0,m.start()),open);
        boolean android=Pattern.compile("\\bextends\\s+(?:[\\w.]+\\.)?(Activity|Fragment|Service|BroadcastReceiver|ContentProvider)\\b").matcher(header).find();
        List<Field> fields=new ArrayList<Field>();
        int depth=1, start=open+1;
        for (int i=open+1; i<close; i++) {
            char c=masked.charAt(i);
            if (c=='{') {depth++; if(depth==2) start=-1;}
            else if (c=='}') {depth--; if(depth==1) start=i+1;}
            else if (c==';' && depth==1) {
                if(start>=0) {
                    Field field=parseField(masked.substring(start,i+1));
                    if(field!=null) fields.add(field);
                }
                start=i+1;
            }
        }
        return new ClassInfo(name,open,close,fields,android);
    }

    private static Field parseField(String segment) {
        String s=segment.trim().replaceAll("(?s)^(@[\\w.]+(?:\\([^;]*?\\))?\\s*)+", "");
        if (s.indexOf('(')>=0 && (s.indexOf('=')<0 || s.indexOf('(')<s.indexOf('='))) return null;
        Matcher m=Pattern.compile("(?s)^((?:(?:public|protected|private|static|final|transient|volatile)\\s+)*)"
            + "([A-Za-z_$][\\w.$<>?, \\[\\]]*)\\s+([A-Za-z_$][\\w$]*)\\s*(=\\s*.*?)?;\\s*$").matcher(s);
        if(!m.matches()) return null;
        String modifiers=" "+m.group(1)+" ";
        String type=m.group(2).trim().replaceAll("\\s+", " ");
        if(type.endsWith(",") || type.indexOf('=')>=0 || type.contains(";")) return null;
        if(type.matches("(?s).*\\b(?:class|interface|enum|return|throws|new|import)\\b.*")) return null;
        String name=m.group(3);
        if(!RefactorEngine.identifier(name)) return null;
        return new Field(name,type,modifiers.contains(" static "),modifiers.contains(" final "),m.group(4)!=null);
    }

    private static List<Field> selectedFields(ClassInfo info, Set<String> selected, boolean constructor) throws IOException {
        List<Field> found = new ArrayList<Field>();
        for (Field f: info.fields) {
            if (selected.contains(f.name) && (!constructor || (!f.isStatic && !(f.isFinal && f.hasInitializer)))) found.add(f);
        }
        if (found.isEmpty() && !(constructor && info.fields.isEmpty())) throw fail("Select at least one eligible field.");
        return found;
    }
    private static boolean methodExists(String src, String name) {
        return Pattern.compile("\\b"+Pattern.quote(name)+"\\s*\\(").matcher(withoutNonCode(src)).find();
    }
    private static String getterName(Field f) {
        if (f.type.equals("boolean") && f.name.startsWith("is") && f.name.length()>2
                && Character.isUpperCase(f.name.charAt(2))) return f.name;
        return (f.type.equals("boolean") ? "is" : "get")+cap(f.name);
    }
    private static String setterName(Field f) {
        if (f.type.equals("boolean") && f.name.startsWith("is") && f.name.length()>2
                && Character.isUpperCase(f.name.charAt(2))) return "set"+f.name.substring(2);
        return "set"+cap(f.name);
    }
    private static String insertMembers(String src, ClassInfo info, String added) {
        String eol=nl(src);
        String updated=src.substring(0,info.close) + eol+added+eol+src.substring(info.close);
        return updated;
    }
    public static RefactorEngine.Plan createAccessors(File file, Set<String> selected) throws IOException {
        String src=RefactorEngine.read(file); ClassInfo info=inspectClass(src);
        List<Field> fields=selectedFields(info,selected,false);
        String eol=nl(src); StringBuilder added=new StringBuilder(); int n=0;
        for (Field f: fields) {
            String g=getterName(f), set=setterName(f);
            String staticKw=f.isStatic ? "static " : "";
            String receiver=f.isStatic ? info.name+"." : "this.";
            if (!methodExists(src,g)) {
                added.append("    public ").append(staticKw).append(f.type).append(' ').append(g)
                    .append("() {").append(eol).append("        return ").append(receiver).append(f.name)
                    .append(';').append(eol).append("    }").append(eol).append(eol);
                n++;
            }
            if (!f.isFinal && !methodExists(src,set)) {
                added.append("    public ").append(staticKw).append("void ").append(set).append('(')
                    .append(f.type).append(' ').append(f.name).append(") {").append(eol)
                    .append("        ").append(receiver).append(f.name).append(" = ").append(f.name)
                    .append(';').append(eol).append("    }").append(eol).append(eol);
                n++;
            }
        }
        if(n==0) throw fail("Selected fields already have matching accessor names, or cannot have setters (final).");
        RefactorEngine.Plan p=new RefactorEngine.Plan("Generate " + n + " Java getter/setter(s)."
            + "\nOnly straightforward class fields are recognized; existing methods are checked by name.");
        p.add(file,file,src,insertMembers(src,info,added.toString()),n); p.openAfter=file; return p;
    }

    public static RefactorEngine.Plan createConstructor(File file, Set<String> selected) throws IOException {
        String src=RefactorEngine.read(file); ClassInfo info=inspectClass(src);
        List<Field> fields=selectedFields(info,selected,true);
        String eol=nl(src); StringBuilder sig=new StringBuilder(), body=new StringBuilder();
        for (int i=0; i<fields.size(); i++) {
            Field f=fields.get(i);
            if (i>0) sig.append(", ");
            sig.append(f.type).append(' ').append(f.name);
            body.append("        this.").append(f.name).append(" = ").append(f.name).append(';').append(eol);
        }
        // Reject exact same parameter-type sequence (conservative signature check).
        String masked=withoutNonCode(src);
        Matcher existing=Pattern.compile("\\b"+Pattern.quote(info.name)+"\\s*\\(([^)]*)\\)").matcher(masked);
        List<String> wanted=new ArrayList<String>();
        for(Field f:fields) wanted.add(f.type.replaceAll("\\s+",""));
        while(existing.find()) {
            String param=existing.group(1).trim();
            if (param.length()==0 && wanted.isEmpty()) throw fail("A no-argument constructor already exists.");
            if (param.length()==0) continue;
            String[] tokens=param.split(",");
            if(tokens.length!=wanted.size()) continue;
            boolean same=true;
            for(int i=0;i<tokens.length;i++) {
                String compact=tokens[i].replaceAll("\\bfinal\\b", "").trim().replaceAll("\\s+", " ");
                int space=compact.lastIndexOf(' ');
                if(space<0 || !compact.substring(0,space).replace(" ","").equals(wanted.get(i))) {same=false; break;}
            }
            if(same) throw fail("A constructor with these parameter types already exists.");
        }
        StringBuilder add=new StringBuilder();
        add.append("    public ").append(info.name).append('(').append(sig).append(") {").append(eol)
            .append(body).append("    }").append(eol);
        RefactorEngine.Plan p=new RefactorEngine.Plan("Create constructor for " + info.name + " ("+fields.size()+" field(s))."
            + (info.androidComponent ? "\nWARNING: Android framework components must support the system's required construction rules. Avoid non-default constructors for Activities/Services." : "")
            + "\nThe generator handles only straightforward field declarations.");
        p.add(file,file,src,insertMembers(src,info,add.toString()),1); p.openAfter=file; return p;
    }

    /** Find the string literal containing a selection/caret, not in comments/chars. */
    static int[] stringLiteral(String src, int a, int b) throws IOException {
        if (a<0 || b<a || b>src.length()) throw fail("Place cursor inside a Java string literal.");
        int i=0;
        while(i<src.length()) {
            char c=src.charAt(i);
            if (i+1<src.length() && c=='/' && src.charAt(i+1)=='/') {
                i+=2; while(i<src.length() && src.charAt(i)!='\n') i++; continue;
            }
            if (i+1<src.length() && c=='/' && src.charAt(i+1)=='*') {
                int next=src.indexOf("*/",i+2); i=next<0 ? src.length() : next+2; continue;
            }
            if(c=='\'' || c=='"') {
                char quote=c; int begin=i++;
                if (quote=='"' && src.startsWith("\"\"",begin)) throw fail("Java text blocks are not supported by Extract Resources.");
                while(i<src.length()) {
                    if(src.charAt(i)=='\\') {i+=2;continue;}
                    if(src.charAt(i)==quote) {i++;break;}
                    if(src.charAt(i)=='\n') break;
                    i++;
                }
                if(quote=='"' && i<=src.length() && i>begin+1 && src.charAt(i-1)=='"'
                    && a>=begin && b<=i && (a<i && b>=begin)) return new int[]{begin,i};
                continue;
            }
            i++;
        }
        throw fail("Select a single quoted Java string literal (or place the cursor inside one).");
    }

    private static String javaUnescape(String value) throws IOException {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c!='\\') {b.append(c);continue;}
            if(++i>=value.length()) throw fail("Invalid Java string escape.");
            c=value.charAt(i);
            if(c=='n') b.append('\n'); else if(c=='r') b.append('\r');
            else if(c=='t') b.append('\t'); else if(c=='b') b.append('\b');
            else if(c=='f') b.append('\f'); else if(c=='\\') b.append('\\');
            else if(c=='\"') b.append('"'); else if(c=='\'') b.append('\'');
            else if(c=='u') {
                while(i+1<value.length() && value.charAt(i+1)=='u') i++;
                if(i+4>=value.length()) throw fail("Incomplete Java unicode escape.");
                try {b.append((char)Integer.parseInt(value.substring(i+1,i+5),16));}
                catch(NumberFormatException e) {throw fail("Invalid Java unicode escape.");}
                i+=4;
            } else if(c>='0' && c<='7') {
                int digit=c-'0'; int max=c<='3' ? 2 : 1;
                for(int k=0;k<max && i+1<value.length() && value.charAt(i+1)>='0'
                    && value.charAt(i+1)<='7';k++) digit=digit*8+(value.charAt(++i)-'0');
                b.append((char)digit);
            } else throw fail("Unsupported Java escape sequence: \\"+c);
        }
        return b.toString();
    }
    private static String androidXmlEscape(String s) throws IOException {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if ((c<32 && c!='\n' && c!='\t' && c!='\r') || c==0xFFFE || c==0xFFFF)
                throw fail("String has XML-invalid control characters.");
            if(c=='&') b.append("&amp;"); else if(c=='<') b.append("&lt;");
            else if(c=='>') b.append("&gt;"); else if(c=='\'') b.append("\\'");
            else if(c=='"') b.append("\\\""); else if(c=='\\') b.append("\\\\");
            else if(c=='\n') b.append("\\n"); else if(c=='\r') b.append("\\r");
            else if(c=='\t') b.append("\\t"); else b.append(c);
        }
        if(b.length()>0 && (b.charAt(0)=='@' || b.charAt(0)=='?')) b.insert(0,'\\');
        return b.toString();
    }
    public static RefactorEngine.Plan extractResource(File file, int a, int b, String name) throws IOException {
        if(!name.matches("[a-z][a-z0-9_]*") || name.equals("string")) throw fail("Resource name must be lowercase with letters, numbers and underscores (e.g. welcome_text).");
        File javaRoot=RefactorEngine.javaSourceRoot(file);
        if(javaRoot==null) throw fail("Extract Resources requires an Android Gradle src/main/java file.");
        String src=RefactorEngine.read(file);
        int[] bounds=stringLiteral(src,a,b);
        String raw=src.substring(bounds[0]+1,bounds[1]-1);
        if (RefactorEngine.isKotlin(file)) {
            if (Pattern.compile("(?<!\\\\)\\$[{A-Za-z_]").matcher(raw).find())
                throw fail("This Kotlin string contains a $ template; extract only plain text literals.");
            raw=raw.replace("\\$","$");
        }
        String value=javaUnescape(raw);
        // Avoid extracting annotation constants / compile-time constants, which need Java literals.
        int line=src.lastIndexOf('\n',bounds[0])+1;
        String earlier=src.substring(line,bounds[0]);
        if(earlier.contains("@") || earlier.matches("(?s).*\\bcase\\s*"))
            throw fail("Annotation values and case labels must remain Java compile-time constants.");
        File xml=new File(javaRoot.getParentFile(),"res/values/strings.xml");
        String old=xml.exists() ? RefactorEngine.read(xml) : "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n";
        if(!old.contains("<resources") || !old.contains("</resources>")) throw fail("Invalid strings.xml: no <resources> root.");
        Matcher exists=Pattern.compile("<string\\s+[^>]*name\\s*=\\s*[\"']"+Pattern.quote(name)+"[\"']").matcher(old);
        if(exists.find()) throw fail("Resource name already exists: "+name);
        int index=old.lastIndexOf("</resources>");
        String eol=nl(old);
        String ins="    <string name=\""+name+"\" formatted=\"false\">"+androidXmlEscape(value)+"</string>"+eol;
        String updatedXml=old.substring(0,index)+ins+old.substring(index);
        String updatedJava=src.substring(0,bounds[0])+"getString(R.string."+name+")"+src.substring(bounds[1]);
        RefactorEngine.Plan p=new RefactorEngine.Plan("Extract Java string literal to res/values/strings.xml as @string/"+name
            + "\nReplaces Java literal with getString(R.string."+name+"). This expression requires an Activity/Context."
            + " If you are in a static method, adapter, or constant expression, adjust the call after review.");
        p.add(file,file,src,updatedJava,1);
        if(xml.exists()) p.add(xml,xml,old,updatedXml,1);
        else p.addCreated(xml,updatedXml);
        p.openAfter=file;
        return p;
    }

    /** Literal or regex text replacement, in one file or the project. */
    public static RefactorEngine.Plan replaceTextually(File current, File root, String find, String replacement,
                                                        boolean project, boolean regex, boolean caseSensitive) throws IOException {
        if(find.length()==0) throw fail("Find text cannot be empty.");
        if(find.length()>500) throw fail("Find text is too long.");
        List<File> files=new ArrayList<File>();
        if(project) {
            if(root==null || !root.isDirectory()) throw fail("Project folder not found.");
            RefactorEngine.collectJava(root,files,0);
        } else files.add(current);
        final Pattern pattern;
        try {pattern=Pattern.compile(regex?find:Pattern.quote(find),
                (caseSensitive?0:Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) | Pattern.MULTILINE);}
        catch(Exception ex) {throw fail("Invalid regex: "+ex.getMessage());}
        RefactorEngine.Plan p=new RefactorEngine.Plan("Replace Textually: "+find+" → "+replacement
            + "\nScope: "+(project?"all Java source files in project":"current file")
            + ", "+(regex?"regular expression":"literal text")+", "+(caseSensitive?"match case":"ignore case")
            + "\nTextual replacement includes comments and string literals. Review every file before applying.");
        for(File f:files) {
            String src=RefactorEngine.read(f);
            Matcher m=pattern.matcher(src);
            StringBuffer b=new StringBuffer(); int n=0;
            try {
                while(m.find()) {
                    if(n++>=20000) throw fail("Too many matches in one file; narrow your query.");
                    m.appendReplacement(b, regex ? replacement : Matcher.quoteReplacement(replacement));
                }
                if(n>0) {
                    m.appendTail(b);
                    p.add(f,f,src,b.toString(),n);
                }
            } catch(IllegalArgumentException e) {throw fail("Invalid regex replacement: "+e.getMessage());}
            if(f.equals(current) && n>0) p.openAfter=f;
        }
        if(p.changes.isEmpty()) throw fail("No matches found.");
        return p;
    }
}
