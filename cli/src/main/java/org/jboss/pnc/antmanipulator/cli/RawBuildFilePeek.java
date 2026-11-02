package org.jboss.pnc.antmanipulator.cli;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.PathTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lightweight, raw peek at a single Ant build file. This is the Ant analogue of
 * pom-manipulation-ext's {@code PomPeek}: it reads just enough via a plain XML parse to discover how
 * this file links to <em>other</em> build files, without executing any tasks or resolving the full
 * effective model.
 *
 * <p>
 * Where a Maven POM links to other POMs through {@code <parent>} and {@code <modules>}, an Ant
 * build file links through the {@code <import>}, {@code <include>}, {@code <ant>} and {@code <subant>}
 * tasks. Those references are collected here as {@link Reference}s and followed by
 * {@link BuildFileScanner}.
 *
 * <p>
 * A raw parse is used (rather than Ant's {@code ProjectHelper}) for two reasons that mirror PME's
 * design: (1) {@code ProjectHelper} executes top-level tasks at parse time, which can fail on missing
 * antlibs; and (2) discovery must be cheap and side-effect free. The richer effective model is read
 * separately by {@link BuildFilePeek} once the hierarchy is known.
 */
public class RawBuildFilePeek {

    private static final Logger logger = LoggerFactory.getLogger(RawBuildFilePeek.class);

    /** The kind of inter-build-file link. */
    public enum Kind {
        IMPORT, INCLUDE, ANT, SUBANT
    }

    /** A single reference from this build file to another build file. */
    public static final class Reference {
        private final Kind kind;
        private final String rawPath;
        private final File resolved; // null when the path could not be resolved statically (e.g. ${...})

        Reference(Kind kind, String rawPath, File resolved) {
            this.kind = kind;
            this.rawPath = rawPath;
            this.resolved = resolved;
        }

        public Kind getKind() {
            return kind;
        }

        public String getRawPath() {
            return rawPath;
        }

        /** The resolved target file, or {@code null} if it contains unresolved {@code ${...}} properties. */
        public File getResolved() {
            return resolved;
        }

        @Override
        public String toString() {
            return kind + " " + rawPath + (resolved == null ? " (unresolved)" : " -> " + resolved);
        }
    }

    private final File buildFile;
    private final String projectName;
    private final String defaultTarget;
    private final String declaredBasedir;
    private final List<Reference> references;

    private RawBuildFilePeek(
            File buildFile,
            String projectName,
            String defaultTarget,
            String declaredBasedir,
            List<Reference> references) {
        this.buildFile = buildFile;
        this.projectName = projectName;
        this.defaultTarget = defaultTarget;
        this.declaredBasedir = declaredBasedir;
        this.references = references;
    }

    public static RawBuildFilePeek peek(File buildFile) throws Exception {
        return peek(buildFile, Collections.emptyMap());
    }

    public static RawBuildFilePeek peek(File buildFile, Map<String, String> initialProperties) throws Exception {
        final File canonical = buildFile.getCanonicalFile();
        final File dir = canonical.getParentFile();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setExpandEntityReferences(false);
        // Discovery reads untrusted build files; disable external entity resolution.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(canonical);

        Element root = doc.getDocumentElement();
        String declaredBasedirRaw = attrOrNull(root, "basedir");

        // Temporary basedir for resolving property files
        File tempBasedir = resolveBasedir(dir, declaredBasedirRaw);
        Map<String, String> properties = collectProperties(root, tempBasedir, initialProperties);

        String projectName = expandProperties(attrOrNull(root, "name"), properties);
        String defaultTarget = expandProperties(attrOrNull(root, "default"), properties);
        String declaredBasedir = expandProperties(declaredBasedirRaw, properties);

        // basedir defaults to the build file's own directory.
        File basedir = resolveBasedir(dir, declaredBasedir);

        List<Reference> refs = new ArrayList<>();
        collectReferences(root, dir, basedir, properties, refs);

        return new RawBuildFilePeek(canonical, projectName, defaultTarget, declaredBasedir, refs);
    }

    private static Map<String, String> collectProperties(
            Element root,
            File basedir,
            Map<String, String> initialProps) {
        Map<String, String> props = new LinkedHashMap<>();
        if (initialProps != null) {
            props.putAll(initialProps);
        }
        collectPropertiesRecursive(root, basedir, props);
        return props;
    }

    private static void collectPropertiesRecursive(Node node, File basedir, Map<String, String> props) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) child;
            if (!conditionHolds(el, props)) {
                // A <target> (or a <property> task's own if/unless) whose condition is known false
                // against properties seen so far in this lightweight peek: a full build execution
                // could still set either attribute's property later, or via a task this peek never
                // runs (e.g. <os family="...">), which we can't see — so this only ever excludes a
                // definition we're confident never runs, never force-includes one we're unsure of.
                continue;
            }
            if ("property".equals(el.getTagName())) {
                String fileAttr = attrOrNull(el, "file");
                if (fileAttr != null) {
                    fileAttr = expandProperties(fileAttr, props);
                    File propFile = new File(fileAttr);
                    if (!propFile.isAbsolute()) {
                        propFile = new File(basedir, fileAttr);
                    }
                    if (propFile.exists() && propFile.isFile()) {
                        try (InputStream in = Files.newInputStream(propFile.toPath())) {
                            Properties p = new Properties();
                            p.load(in);
                            for (String name : p.stringPropertyNames()) {
                                if (!props.containsKey(name)) {
                                    props.put(name, p.getProperty(name));
                                }
                            }
                        } catch (Exception e) {
                            logger.debug(
                                    "Could not load property file {} during lightweight peek: {}",
                                    propFile,
                                    e.getMessage());
                        }
                    }
                }
                String name = attrOrNull(el, "name");
                String value = attrOrNull(el, "value");
                String location = attrOrNull(el, "location");
                if (name != null && !props.containsKey(name)) {
                    if (value != null) {
                        props.put(name, expandProperties(value, props));
                    } else if (location != null) {
                        String expLoc = expandProperties(location, props);
                        File locFile = new File(expLoc);
                        if (!locFile.isAbsolute()) {
                            locFile = new File(basedir, expLoc);
                        }
                        props.put(name, locFile.getAbsolutePath());
                    }
                }
            }
            collectPropertiesRecursive(el, basedir, props);
        }
    }

    /**
     * Best-effort Ant {@code if}/{@code unless} evaluation against properties already known from this
     * lightweight peek (including {@code initialProperties} and definitions earlier in document order).
     * A full build execution could set either attribute's named property later, or via a task this peek
     * never runs (e.g. an {@code <os family="...">} check) — this can't see that, so it only ever
     * <em>excludes</em> a definition it's confident never runs, never force-includes one it isn't sure of.
     */
    private static boolean conditionHolds(Element el, Map<String, String> props) {
        String ifAttr = attrOrNull(el, "if");
        if (ifAttr != null && !props.containsKey(ifAttr)) {
            return false;
        }
        String unlessAttr = attrOrNull(el, "unless");
        return unlessAttr == null || !props.containsKey(unlessAttr);
    }

    /**
     * Expands every {@code ${property}} reference in {@code str} that resolves against {@code props},
     * repeating until nothing more changes (so a chain like {@code ${a}} -> {@code ${b}} -> a literal
     * resolves fully) or {@code depth} iterations are exhausted, whichever comes first. Each iteration
     * substitutes <em>every</em> currently-resolvable token, not just the first one found, so a string
     * with many distinct references (e.g. a long {@code buildpath}) fully resolves in one pass rather
     * than needing one iteration per token.
     */
    private static String expandProperties(String str, Map<String, String> props) {
        if (str == null || !str.contains("${") || props == null || props.isEmpty()) {
            return str;
        }
        String current = str;
        for (int depth = 0; depth < 10 && current.contains("${"); depth++) {
            String next = current;
            for (Map.Entry<String, String> entry : props.entrySet()) {
                String token = "${" + entry.getKey() + "}";
                if (next.contains(token)) {
                    next = next.replace(token, entry.getValue());
                }
            }
            if (next.equals(current)) {
                break; // no further substitutions possible
            }
            current = next;
        }
        return current;
    }

    private static void collectReferences(
            Node node,
            File importDir,
            File basedir,
            Map<String, String> props,
            List<Reference> refs) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) child;
            switch (el.getTagName()) {
                case "import":
                    addRef(refs, Kind.IMPORT, expandProperties(attrOrNull(el, "file"), props), importDir);
                    break;
                case "include":
                    addRef(refs, Kind.INCLUDE, expandProperties(attrOrNull(el, "file"), props), importDir);
                    break;
                case "ant":
                    collectAntReference(el, basedir, props, refs);
                    break;
                case "subant":
                    collectSubantReferences(el, basedir, props, refs);
                    break;
                default:
                    collectReferences(el, importDir, basedir, props, refs);
                    break;
            }
        }
    }

    private static void collectAntReference(
            Element el,
            File basedir,
            Map<String, String> props,
            List<Reference> refs) {
        String antfile = expandProperties(attrOrNull(el, "antfile"), props);
        String dirAttr = expandProperties(attrOrNull(el, "dir"), props);

        if (antfile == null || antfile.isEmpty()) {
            antfile = "build.xml";
        }

        if (dirAttr != null && !dirAttr.isEmpty()) {
            if (dirAttr.contains("${")) {
                refs.add(new Reference(Kind.ANT, dirAttr + "/" + antfile, null));
                return;
            }
            File d = new File(dirAttr);
            File targetDir = d.isAbsolute() ? d : new File(basedir, dirAttr);
            String rawPath = dirAttr.equals(".") ? antfile : dirAttr + "/" + antfile;
            File resolved = resolve(antfile, targetDir);
            refs.add(new Reference(Kind.ANT, rawPath, resolved));
        } else {
            addRef(refs, Kind.ANT, antfile, basedir);
        }
    }

    private static void collectSubantReferences(
            Element el,
            File basedir,
            Map<String, String> props,
            List<Reference> refs) {
        String antfile = expandProperties(attrOrNull(el, "antfile"), props);
        if (antfile == null || antfile.isEmpty()) {
            antfile = expandProperties(attrOrNull(el, "genericantfile"), props);
        }
        if (antfile == null || antfile.isEmpty()) {
            antfile = "build.xml";
        }

        // Whether any nested construct actually contributed a reference (resolved or not) — not just
        // whether one was *present* in the XML. A <fileset>/<dirset> whose dir doesn't exist yet, or
        // whose includes pattern matches nothing, contributes zero references despite being present, and
        // must still fall through to the approximate single reference below rather than silently
        // resolving to nothing.
        int refsBeforeNested = refs.size();

        String buildpathAttr = expandProperties(attrOrNull(el, "buildpath"), props);
        if (buildpathAttr != null && !buildpathAttr.isEmpty()) {
            processPathString(buildpathAttr, antfile, basedir, refs);
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element childEl = (Element) child;
            switch (childEl.getTagName()) {
                case "fileset":
                    processFileset(childEl, basedir, props, refs);
                    break;
                case "dirset":
                    processDirset(childEl, antfile, basedir, props, refs);
                    break;
                case "filelist":
                    processFilelist(childEl, basedir, props, refs);
                    break;
                case "buildpath":
                case "path":
                    processBuildpath(childEl, antfile, basedir, props, refs);
                    break;
                case "pathelement":
                    processPathelement(childEl, antfile, basedir, props, refs);
                    break;
                default:
                    break;
            }
        }

        if (refs.size() == refsBeforeNested) {
            addRef(refs, Kind.SUBANT, antfile, basedir);
        }
    }

    private static void processFileset(
            Element filesetEl,
            File basedir,
            Map<String, String> props,
            List<Reference> refs) {
        String dirAttr = expandProperties(attrOrNull(filesetEl, "dir"), props);
        if (dirAttr == null) {
            dirAttr = expandProperties(attrOrNull(filesetEl, "basedir"), props);
        }
        if (dirAttr != null && dirAttr.contains("${")) {
            refs.add(new Reference(Kind.SUBANT, dirAttr, null));
            return;
        }
        File base = (dirAttr == null || dirAttr.isEmpty()) ? basedir : resolveBaseDir(basedir, dirAttr);
        if (base == null || !base.exists() || !base.isDirectory()) {
            return;
        }

        List<String> includes = collectPatterns(filesetEl, "includes", "include", props);
        List<String> excludes = collectPatterns(filesetEl, "excludes", "exclude", props);

        DirectoryScanner ds = new DirectoryScanner();
        ds.setBasedir(base);
        ds.addDefaultExcludes(); // matches real Ant <fileset>/<dirset> default excludes (.git, .svn, CVS, etc.)
        if (!includes.isEmpty()) {
            ds.setIncludes(includes.toArray(new String[0]));
        }
        if (!excludes.isEmpty()) {
            ds.setExcludes(excludes.toArray(new String[0]));
        }
        ds.scan();
        for (String includedFile : ds.getIncludedFiles()) {
            File f = new File(base, includedFile);
            addCanonicalOrAbsoluteRef(refs, Kind.SUBANT, includedFile, f);
        }
    }

    private static void processDirset(
            Element dirsetEl,
            String antfile,
            File basedir,
            Map<String, String> props,
            List<Reference> refs) {
        String dirAttr = expandProperties(attrOrNull(dirsetEl, "dir"), props);
        if (dirAttr == null) {
            dirAttr = expandProperties(attrOrNull(dirsetEl, "basedir"), props);
        }
        if (dirAttr != null && dirAttr.contains("${")) {
            refs.add(new Reference(Kind.SUBANT, dirAttr, null));
            return;
        }
        File base = (dirAttr == null || dirAttr.isEmpty()) ? basedir : resolveBaseDir(basedir, dirAttr);
        if (base == null || !base.exists() || !base.isDirectory()) {
            return;
        }

        List<String> includes = collectPatterns(dirsetEl, "includes", "include", props);
        List<String> excludes = collectPatterns(dirsetEl, "excludes", "exclude", props);

        DirectoryScanner ds = new DirectoryScanner();
        ds.setBasedir(base);
        ds.addDefaultExcludes(); // matches real Ant <fileset>/<dirset> default excludes (.git, .svn, CVS, etc.)
        if (!includes.isEmpty()) {
            ds.setIncludes(includes.toArray(new String[0]));
        }
        if (!excludes.isEmpty()) {
            ds.setExcludes(excludes.toArray(new String[0]));
        }
        ds.scan();
        for (String includedDir : ds.getIncludedDirectories()) {
            File d = new File(base, includedDir);
            File f = new File(d, antfile);
            String rawPath = (includedDir.isEmpty() || includedDir.equals(".")) ? antfile
                    : includedDir + "/" + antfile;
            addCanonicalOrAbsoluteRef(refs, Kind.SUBANT, rawPath, f);
        }
    }

    private static void processFilelist(
            Element filelistEl,
            File basedir,
            Map<String, String> props,
            List<Reference> refs) {
        String dirAttr = expandProperties(attrOrNull(filelistEl, "dir"), props);
        if (dirAttr != null && dirAttr.contains("${")) {
            refs.add(new Reference(Kind.SUBANT, dirAttr, null));
            return;
        }
        File base = (dirAttr == null || dirAttr.isEmpty()) ? basedir : resolveBaseDir(basedir, dirAttr);
        if (base == null) {
            return;
        }

        String filesAttr = expandProperties(attrOrNull(filelistEl, "files"), props);
        if (filesAttr != null) {
            for (String file : filesAttr.split("[,\\s]+")) {
                file = file.trim();
                if (!file.isEmpty()) {
                    addRef(refs, Kind.SUBANT, file, base);
                }
            }
        }
        NodeList fileNodes = filelistEl.getElementsByTagName("file");
        for (int i = 0; i < fileNodes.getLength(); i++) {
            Element fileEl = (Element) fileNodes.item(i);
            String name = expandProperties(attrOrNull(fileEl, "name"), props);
            if (name != null && !name.trim().isEmpty()) {
                addRef(refs, Kind.SUBANT, name.trim(), base);
            }
        }
    }

    private static void processBuildpath(
            Element buildpathEl,
            String defaultAntfile,
            File basedir,
            Map<String, String> props,
            List<Reference> refs) {
        String pathAttr = expandProperties(attrOrNull(buildpathEl, "path"), props);
        if (pathAttr != null && !pathAttr.isEmpty()) {
            processPathString(pathAttr, defaultAntfile, basedir, refs);
        }
        String locationAttr = expandProperties(attrOrNull(buildpathEl, "location"), props);
        if (locationAttr != null && !locationAttr.isEmpty()) {
            processPathElement(locationAttr, defaultAntfile, basedir, refs);
        }

        NodeList children = buildpathEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element childEl = (Element) child;
            switch (childEl.getTagName()) {
                case "pathelement":
                    processPathelement(childEl, defaultAntfile, basedir, props, refs);
                    break;
                case "fileset":
                    processFileset(childEl, basedir, props, refs);
                    break;
                case "dirset":
                    processDirset(childEl, defaultAntfile, basedir, props, refs);
                    break;
                case "filelist":
                    processFilelist(childEl, basedir, props, refs);
                    break;
                default:
                    break;
            }
        }
    }

    private static void processPathelement(
            Element pathelementEl,
            String defaultAntfile,
            File basedir,
            Map<String, String> props,
            List<Reference> refs) {
        String location = expandProperties(attrOrNull(pathelementEl, "location"), props);
        if (location != null && !location.isEmpty()) {
            processPathElement(location, defaultAntfile, basedir, refs);
        }
        String path = expandProperties(attrOrNull(pathelementEl, "path"), props);
        if (path != null && !path.isEmpty()) {
            processPathString(path, defaultAntfile, basedir, refs);
        }
    }

    /**
     * Splits a {@code buildpath}/{@code path} attribute into its elements using Ant's own
     * {@link PathTokenizer}, rather than a plain regex. Two wins over the old {@code [:;,\s]+} split:
     * it never splits on a comma or whitespace (those aren't path separators in Ant — only {@code :}/
     * {@code ;} are), and on a Windows JVM it special-cases a single-letter drive prefix so
     * {@code C:\modules\a} isn't torn into {@code C} and {@code \modules\a}. That drive-letter case is
     * gated on {@code File.pathSeparatorChar == ';'} inside {@link PathTokenizer} itself, i.e. it only
     * fires when <em>this tool</em> runs on Windows — on a Linux host peeking at a Windows-authored
     * build file, this now matches exactly what a real Ant running on that same Linux host would do,
     * which is the best a lightweight peek can promise.
     */
    private static void processPathString(
            String pathStr,
            String defaultAntfile,
            File basedir,
            List<Reference> refs) {
        PathTokenizer tokenizer = new PathTokenizer(pathStr);
        while (tokenizer.hasMoreTokens()) {
            String part = tokenizer.nextToken().trim();
            if (!part.isEmpty()) {
                processPathElement(part, defaultAntfile, basedir, refs);
            }
        }
    }

    private static void processPathElement(
            String pathElement,
            String defaultAntfile,
            File basedir,
            List<Reference> refs) {
        if (pathElement.contains("${")) {
            refs.add(new Reference(Kind.SUBANT, pathElement, null));
            return;
        }
        File target = new File(pathElement);
        if (!target.isAbsolute()) {
            target = new File(basedir, pathElement);
        }
        if (target.isDirectory()) {
            File buildFile = new File(target, defaultAntfile);
            String rawPath = pathElement + "/" + defaultAntfile;
            addCanonicalOrAbsoluteRef(refs, Kind.SUBANT, rawPath, buildFile);
        } else {
            addCanonicalOrAbsoluteRef(refs, Kind.SUBANT, pathElement, target);
        }
    }

    private static List<String> collectPatterns(
            Element el,
            String attrName,
            String childTag,
            Map<String, String> props) {
        List<String> patterns = new ArrayList<>();
        String attr = expandProperties(attrOrNull(el, attrName), props);
        if (attr != null) {
            String[] parts = attr.split("[,\\s]+");
            for (String p : parts) {
                p = p.trim();
                if (!p.isEmpty()) {
                    patterns.add(p);
                }
            }
        }
        NodeList children = el.getElementsByTagName(childTag);
        for (int i = 0; i < children.getLength(); i++) {
            Element child = (Element) children.item(i);
            String name = expandProperties(attrOrNull(child, "name"), props);
            if (name != null) {
                name = name.trim();
                if (!name.isEmpty()) {
                    patterns.add(name);
                }
            }
        }
        return patterns;
    }

    private static File resolveBaseDir(File basedir, String dirPath) {
        if (dirPath.contains("${")) {
            return null;
        }
        File d = new File(dirPath);
        return d.isAbsolute() ? d : new File(basedir, dirPath);
    }

    private static void addCanonicalOrAbsoluteRef(
            List<Reference> refs,
            Kind kind,
            String rawPath,
            File file) {
        try {
            refs.add(new Reference(kind, rawPath, file.getCanonicalFile()));
        } catch (Exception e) {
            refs.add(new Reference(kind, rawPath, file.getAbsoluteFile()));
        }
    }

    private static void addRef(List<Reference> refs, Kind kind, String rawPath, File base) {
        if (rawPath == null || rawPath.isEmpty()) {
            return;
        }
        File resolved = resolve(rawPath, base);
        refs.add(new Reference(kind, rawPath, resolved));
    }

    /**
     * Resolve a reference path the way Ant does: absolute paths as-is, otherwise relative to
     * {@code base}. Paths containing unresolved {@code ${...}} properties cannot be resolved
     * statically and yield {@code null}.
     */
    private static File resolve(String rawPath, File base) {
        if (rawPath.contains("${")) {
            return null;
        }
        File f = new File(rawPath);
        if (!f.isAbsolute()) {
            f = new File(base, rawPath);
        }
        try {
            return f.getCanonicalFile();
        } catch (Exception e) {
            return f.getAbsoluteFile();
        }
    }

    private static File resolveBasedir(File buildFileDir, String declaredBasedir) {
        if (declaredBasedir == null || declaredBasedir.contains("${")) {
            return buildFileDir;
        }
        File b = new File(declaredBasedir);
        return b.isAbsolute() ? b : new File(buildFileDir, declaredBasedir);
    }

    private static String attrOrNull(Element el, String name) {
        return el.hasAttribute(name) ? el.getAttribute(name) : null;
    }

    public File getBuildFile() {
        return buildFile;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getDefaultTarget() {
        return defaultTarget;
    }

    public String getDeclaredBasedir() {
        return declaredBasedir;
    }

    public List<Reference> getReferences() {
        return references;
    }
}
