package org.bukkit.configuration.file;

import com.google.common.base.Preconditions;
// XMine start - подстановка переменных среды
import io.papermc.paper.configuration.EnvironmentSubstitutor;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;
// XMine end - подстановка переменных среды
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable; // XMine - подстановка переменных среды
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.reader.UnicodeReader;

/**
 * An implementation of {@link Configuration} which saves all files in Yaml.
 * Note that this implementation is not synchronized.
 */
public class YamlConfiguration extends FileConfiguration {
    /**
     * @deprecated unused, not intended to be API
     */
    @Deprecated(since = "1.18.1")
    protected static final String COMMENT_PREFIX = "# ";
    /**
     * @deprecated unused, not intended to be API
     */
    @Deprecated(since = "1.18.1")
    protected static final String BLANK_CONFIG = "{}\n";
    private final DumperOptions yamlDumperOptions;
    private final LoaderOptions yamlLoaderOptions;
    private final YamlConstructor constructor;
    private final YamlRepresenter representer;
    private final Yaml yaml;
    // XMine start - подстановка переменных среды
    /**
     * Templates recorded while loading, keyed by the structural path to the scalar they came
     * from (map keys as strings, sequence positions as ints). {@link #saveToString()} puts the
     * template back so that a plugin rewriting its own configuration does not freeze the
     * expanded value - and, in the case that motivated this feature, a secret - onto disk.
     */
    private final Map<List<Object>, EnvironmentTemplate> environmentTemplates = new HashMap<>();
    /**
     * A decimal integer whose {@code toString} is byte-for-byte what was read. Anything else -
     * {@code 0755}, {@code 1_000}, {@code 0x1F}, {@code -0} - is deliberately left as a string:
     * YAML 1.1 would turn {@code 0755} into 493, and the value written back on the next save
     * would no longer be the value the operator supplied.
     */
    private static final Pattern CANONICAL_INT = Pattern.compile("0|-?[1-9][0-9]*");
    private static final Pattern CANONICAL_FLOAT = Pattern.compile("-?(?:0|[1-9][0-9]*)\\.[0-9]+");

    // @NotNull on the components so that the generated accessors carry it: AnnotationTest
    // requires every object-returning method under org/bukkit to be annotated.
    private record EnvironmentTemplate(@NotNull String template, @NotNull String substituted, @NotNull Tag tag,
                                       @NotNull DumperOptions.ScalarStyle style) {
    }
    // XMine end - подстановка переменных среды

    public YamlConfiguration() {
        yamlDumperOptions = new DumperOptions();
        yamlDumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        yamlLoaderOptions = new LoaderOptions();
        yamlLoaderOptions.setMaxAliasesForCollections(Integer.MAX_VALUE); // SPIGOT-5881: Not ideal, but was default pre SnakeYAML 1.26
        yamlLoaderOptions.setCodePointLimit(Integer.MAX_VALUE); // SPIGOT-7161: Not ideal, but was default pre SnakeYAML 1.32
        yamlLoaderOptions.setNestingDepthLimit(100); // SPIGOT-7906: The default limit (50) can be easily reached with nested bundles

        constructor = new YamlConstructor(yamlLoaderOptions);
        representer = new YamlRepresenter(yamlDumperOptions);
        representer.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        yaml = new Yaml(constructor, representer, yamlDumperOptions, yamlLoaderOptions);
    }

    @NotNull
    @Override
    public String saveToString() {
        yamlDumperOptions.setIndent(options().indent());
        yamlDumperOptions.setWidth(options().width());
        yamlDumperOptions.setProcessComments(options().parseComments());

        MappingNode node = toNodeTree(this);
        restoreEnvironmentTemplates(node, new ArrayList<>()); // XMine - подстановка переменных среды

        node.setBlockComments(getCommentLines(saveHeader(options().getHeader()), CommentType.BLOCK));
        node.setEndComments(getCommentLines(options().getFooter(), CommentType.BLOCK));

        StringWriter writer = new StringWriter();
        if (node.getBlockComments().isEmpty() && node.getEndComments().isEmpty() && node.getValue().isEmpty()) {
            writer.write("");
        } else {
            if (node.getValue().isEmpty()) {
                node.setFlowStyle(DumperOptions.FlowStyle.FLOW);
            }
            yaml.serialize(node, writer);
        }
        return writer.toString();
    }

    @Override
    public void loadFromString(@NotNull String contents) throws InvalidConfigurationException {
        Preconditions.checkArgument(contents != null, "Contents cannot be null");
        yamlLoaderOptions.setProcessComments(options().parseComments());
        yamlLoaderOptions.setCodePointLimit(options().codePointLimit()); // Paper

        MappingNode node;
        try (Reader reader = new UnicodeReader(new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8)))) {
            Node rawNode = yaml.compose(reader);
            try {
                node = (MappingNode) rawNode;
            } catch (ClassCastException e) {
                throw new InvalidConfigurationException("Top level is not a Map.");
            }
        } catch (YAMLException | IOException | ClassCastException e) {
            throw new InvalidConfigurationException(e);
        }

        this.map.clear();
        this.environmentTemplates.clear(); // XMine - подстановка переменных среды

        if (node != null) {
            adjustNodeComments(node);
            options().setHeader(loadHeader(getCommentLines(node.getBlockComments())));
            options().setFooter(getCommentLines(node.getEndComments()));
            // XMine start - подстановка переменных среды
            // Deliberately after compose() and before construction: the document has already
            // been parsed, so an expanded value can neither change the structure of the file
            // nor turn a parse error message into a place a secret can appear.
            if (options().substituteEnvironmentVariables()) {
                substituteEnvironment(node, new ArrayList<>(), new IdentityHashMap<>());
            }
            // XMine end - подстановка переменных среды
            fromNodeTree(node, this);
        }
    }

    // XMine start - подстановка переменных среды
    /**
     * Looks up an environment variable. Overridable so that tests - and any future caller that
     * wants a different source of values - do not have to mutate the real process environment.
     *
     * @param name the variable name
     * @return its value, or {@code null} if it is not set
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    @Nullable
    protected String environmentValue(@NotNull String name) {
        return System.getenv(name);
    }

    /**
     * Rewrites scalars containing environment references, in place, on the composed node tree.
     *
     * @param node the node to visit
     * @param path the structural path to {@code node}
     * @param replaced scalars already replaced, so that YAML aliases follow their anchor
     * @return a node that must take {@code node}'s place in its parent, or {@code null}
     */
    @Nullable
    private Node substituteEnvironment(@NotNull Node node, @NotNull List<Object> path, @NotNull Map<Node, Node> replaced) {
        if (node instanceof AnchorNode anchorNode) {
            // An alias resolves to the very node its anchor defined; if that node was replaced,
            // the alias has to point at the replacement.
            return replaced.get(anchorNode.getRealNode());
        }

        if (node instanceof MappingNode mappingNode) {
            List<NodeTuple> tuples = mappingNode.getValue();
            List<NodeTuple> rebuilt = null;
            for (int i = 0; i < tuples.size(); i++) {
                NodeTuple tuple = tuples.get(i);
                Node key = tuple.getKeyNode();
                // Keys are never substituted: a key is a path segment, and rewriting one would
                // move settings around behind the plugin's back.
                path.add(key instanceof ScalarNode scalarKey ? scalarKey.getValue() : Integer.valueOf(i));
                Node replacement = substituteEnvironment(tuple.getValueNode(), path, replaced);
                path.remove(path.size() - 1);
                if (replacement != null) {
                    if (rebuilt == null) {
                        rebuilt = new ArrayList<>(tuples);
                    }
                    rebuilt.set(i, new NodeTuple(key, replacement));
                }
            }
            if (rebuilt != null) {
                mappingNode.setValue(rebuilt);
            }
            return null;
        }

        if (node instanceof SequenceNode sequenceNode) {
            List<Node> values = sequenceNode.getValue();
            for (int i = 0; i < values.size(); i++) {
                path.add(i);
                Node replacement = substituteEnvironment(values.get(i), path, replaced);
                path.remove(path.size() - 1);
                if (replacement != null) {
                    values.set(i, replacement);
                }
            }
            return null;
        }

        if (!(node instanceof ScalarNode scalarNode)) {
            return null;
        }

        String template = scalarNode.getValue();
        String substituted = EnvironmentSubstitutor.substitute(template, this::environmentValue);
        if (substituted == null) {
            return null;
        }

        Tag tag = scalarNode.getTag();
        DumperOptions.ScalarStyle style = scalarNode.getScalarStyle();
        // An unquoted "${PORT}" should behave like the number it expands to, so that getInt()
        // works. A quoted one stays a string, exactly as a quoted literal would have.
        Tag substitutedTag = scalarNode.isPlain() && Tag.STR.equals(tag) ? implicitTag(substituted) : tag;

        ScalarNode replacement = new ScalarNode(substitutedTag, substituted, scalarNode.getStartMark(), scalarNode.getEndMark(), style);
        replacement.setBlockComments(scalarNode.getBlockComments());
        replacement.setInLineComments(scalarNode.getInLineComments());
        replacement.setEndComments(scalarNode.getEndComments());

        this.environmentTemplates.put(List.copyOf(path), new EnvironmentTemplate(template, substituted, tag, style));
        replaced.put(scalarNode, replacement);
        return replacement;
    }

    /**
     * Resolves the implicit YAML tag of an expanded scalar, but only where the value survives a
     * round trip unchanged. Everything else stays a string rather than risk saving back a value
     * the operator never wrote.
     *
     * @param value the expanded scalar text
     * @return the tag to give the scalar
     */
    @NotNull
    private static Tag implicitTag(@NotNull String value) {
        if (value.isEmpty()) {
            return Tag.STR; // an empty expansion is an empty string, not null
        }
        if (CANONICAL_INT.matcher(value).matches()) {
            return Tag.INT;
        }
        if (value.equals("true") || value.equals("false")) {
            return Tag.BOOL; // not "yes"/"on": those would come back as "true" on the next save
        }
        if (CANONICAL_FLOAT.matcher(value).matches()) {
            try {
                if (Double.toString(Double.parseDouble(value)).equals(value)) {
                    return Tag.FLOAT;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // Everything else - timestamps, sexagesimals, "~", "yes" - stays a string on purpose:
        // the whitelist above is exactly the set of values that survive a save unchanged.
        return Tag.STR;
    }

    /**
     * Puts recorded templates back into the node tree that is about to be written out.
     * <p>
     * A value is only restored when what is about to be saved still equals what the template
     * expanded to. If a plugin has since changed the setting, that new value is written as-is -
     * a deliberate change must never be silently reverted to a template.
     *
     * @param node the node to visit
     * @param path the structural path to {@code node}
     */
    private void restoreEnvironmentTemplates(@NotNull Node node, @NotNull List<Object> path) {
        if (this.environmentTemplates.isEmpty()) {
            return;
        }

        if (node instanceof MappingNode mappingNode) {
            List<NodeTuple> tuples = mappingNode.getValue();
            List<NodeTuple> rebuilt = null;
            for (int i = 0; i < tuples.size(); i++) {
                NodeTuple tuple = tuples.get(i);
                if (!(tuple.getKeyNode() instanceof ScalarNode scalarKey)) {
                    continue;
                }
                path.add(scalarKey.getValue());
                Node value = tuple.getValueNode();
                ScalarNode restored = restoreEnvironmentTemplate(value, path);
                if (restored != null) {
                    if (rebuilt == null) {
                        rebuilt = new ArrayList<>(tuples);
                    }
                    rebuilt.set(i, new NodeTuple(tuple.getKeyNode(), restored));
                } else {
                    restoreEnvironmentTemplates(value, path);
                }
                path.remove(path.size() - 1);
            }
            if (rebuilt != null) {
                mappingNode.setValue(rebuilt);
            }
        } else if (node instanceof SequenceNode sequenceNode) {
            List<Node> values = sequenceNode.getValue();
            for (int i = 0; i < values.size(); i++) {
                path.add(i);
                ScalarNode restored = restoreEnvironmentTemplate(values.get(i), path);
                if (restored != null) {
                    values.set(i, restored);
                } else {
                    restoreEnvironmentTemplates(values.get(i), path);
                }
                path.remove(path.size() - 1);
            }
        }
    }

    @Nullable
    private ScalarNode restoreEnvironmentTemplate(@NotNull Node node, @NotNull List<Object> path) {
        if (!(node instanceof ScalarNode scalarNode)) {
            return null;
        }
        EnvironmentTemplate template = this.environmentTemplates.get(path);
        if (template == null || !template.substituted().equals(scalarNode.getValue())) {
            return null;
        }
        ScalarNode restored = new ScalarNode(template.tag(), template.template(), null, null, template.style());
        restored.setBlockComments(scalarNode.getBlockComments());
        restored.setInLineComments(scalarNode.getInLineComments());
        restored.setEndComments(scalarNode.getEndComments());
        return restored;
    }
    // XMine end - подстановка переменных среды

    /**
     * This method splits the header on the last empty line, and sets the
     * comments below this line as comments for the first key on the map object.
     *
     * @param node The root node of the yaml object
     */
    private void adjustNodeComments(final MappingNode node) {
        if (node.getBlockComments() == null && !node.getValue().isEmpty()) {
            Node firstNode = node.getValue().get(0).getKeyNode();
            List<CommentLine> lines = firstNode.getBlockComments();
            if (lines != null) {
                int index = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).getCommentType() == CommentType.BLANK_LINE) {
                        index = i;
                    }
                }
                if (index != -1) {
                    node.setBlockComments(lines.subList(0, index + 1));
                    firstNode.setBlockComments(lines.subList(index + 1, lines.size()));
                }
            }
        }
    }

    private void fromNodeTree(@NotNull MappingNode input, @NotNull ConfigurationSection section) {
        constructor.flattenMapping(input);
        for (NodeTuple nodeTuple : input.getValue()) {
            Node key = nodeTuple.getKeyNode();
            String keyString = String.valueOf(constructor.construct(key));
            Node value = nodeTuple.getValueNode();

            while (value instanceof AnchorNode) {
                value = ((AnchorNode) value).getRealNode();
            }

            if (value instanceof MappingNode && !hasSerializedTypeKey((MappingNode) value)) {
                fromNodeTree((MappingNode) value, section.createSection(keyString));
            } else {
                section.set(keyString, constructor.construct(value));
            }

            section.setComments(keyString, getCommentLines(key.getBlockComments()));
            if (value instanceof MappingNode || value instanceof SequenceNode) {
                section.setInlineComments(keyString, getCommentLines(key.getInLineComments()));
            } else {
                section.setInlineComments(keyString, getCommentLines(value.getInLineComments()));
            }
        }
    }

    private boolean hasSerializedTypeKey(MappingNode node) {
        for (NodeTuple nodeTuple : node.getValue()) {
            Node keyNode = nodeTuple.getKeyNode();
            if (!(keyNode instanceof ScalarNode)) continue;
            String key = ((ScalarNode) keyNode).getValue();
            if (key.equals(ConfigurationSerialization.SERIALIZED_TYPE_KEY)) {
                return true;
            }
        }
        return false;
    }

    private MappingNode toNodeTree(@NotNull ConfigurationSection section) {
        List<NodeTuple> nodeTuples = new ArrayList<>();
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            Node key = representer.represent(entry.getKey());
            Node value;
            if (entry.getValue() instanceof ConfigurationSection) {
                value = toNodeTree((ConfigurationSection) entry.getValue());
            } else {
                value = representer.represent(entry.getValue());
            }
            key.setBlockComments(getCommentLines(section.getComments(entry.getKey()), CommentType.BLOCK));
            if (value instanceof MappingNode || value instanceof SequenceNode) {
                key.setInLineComments(getCommentLines(section.getInlineComments(entry.getKey()), CommentType.IN_LINE));
            } else {
                value.setInLineComments(getCommentLines(section.getInlineComments(entry.getKey()), CommentType.IN_LINE));
            }

            nodeTuples.add(new NodeTuple(key, value));
        }

        return new MappingNode(Tag.MAP, nodeTuples, DumperOptions.FlowStyle.BLOCK);
    }

    private List<String> getCommentLines(List<CommentLine> comments) {
        List<String> lines = new ArrayList<>();
        if (comments != null) {
            for (CommentLine comment : comments) {
                if (comment.getCommentType() == CommentType.BLANK_LINE) {
                    lines.add(null);
                } else {
                    String line = comment.getValue();
                    line = line.startsWith(" ") ? line.substring(1) : line;
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private List<CommentLine> getCommentLines(List<String> comments, CommentType commentType) {
        List<CommentLine> lines = new ArrayList<CommentLine>();
        for (String comment : comments) {
            if (comment == null) {
                lines.add(new CommentLine(null, null, "", CommentType.BLANK_LINE));
            } else {
                String line = comment;
                line = line.isEmpty() ? line : " " + line;
                lines.add(new CommentLine(null, null, line, commentType));
            }
        }
        return lines;
    }

    /**
     * Removes the empty line at the end of the header that separates the header
     * from further comments. Also removes all empty header starts (backwards
     * compat).
     *
     * @param header The list of heading comments
     * @return The modified list
     */
    private List<String> loadHeader(List<String> header) {
        LinkedList<String> list = new LinkedList<>(header);

        if (!list.isEmpty()) {
            list.removeLast();
        }

        while (!list.isEmpty() && list.peek() == null) {
            list.remove();
        }

        return list;
    }

    /**
     * Adds the empty line at the end of the header that separates the header
     * from further comments.
     *
     * @param header The list of heading comments
     * @return The modified list
     */
    private List<String> saveHeader(List<String> header) {
        LinkedList<String> list = new LinkedList<>(header);

        if (!list.isEmpty()) {
            list.add(null);
        }

        return list;
    }

    @NotNull
    @Override
    public YamlConfigurationOptions options() {
        if (options == null) {
            options = new YamlConfigurationOptions(this);
        }

        return (YamlConfigurationOptions) options;
    }

    /**
     * Creates a new {@link YamlConfiguration}, loading from the given file.
     * <p>
     * Any errors loading the Configuration will be logged and then ignored.
     * If the specified input is not a valid config, a blank config will be
     * returned.
     * <p>
     * The encoding used may follow the system dependent default.
     *
     * @param file Input file
     * @return Resulting configuration
     * @throws IllegalArgumentException Thrown if file is null
     */
    @NotNull
    public static YamlConfiguration loadConfiguration(@NotNull File file) {
        Preconditions.checkArgument(file != null, "File cannot be null");

        YamlConfiguration config = new YamlConfiguration();

        try {
            config.load(file);
        } catch (FileNotFoundException ex) {
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Cannot load " + file, ex);
        } catch (InvalidConfigurationException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Cannot load " + file, ex);
        }

        return config;
    }

    /**
     * Creates a new {@link YamlConfiguration}, loading from the given reader.
     * <p>
     * Any errors loading the Configuration will be logged and then ignored.
     * If the specified input is not a valid config, a blank config will be
     * returned.
     *
     * @param reader input
     * @return resulting configuration
     * @throws IllegalArgumentException Thrown if stream is null
     */
    @NotNull
    public static YamlConfiguration loadConfiguration(@NotNull Reader reader) {
        Preconditions.checkArgument(reader != null, "Stream cannot be null");

        YamlConfiguration config = new YamlConfiguration();

        try {
            config.load(reader);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Cannot load configuration from stream", ex);
        } catch (InvalidConfigurationException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Cannot load configuration from stream", ex);
        }

        return config;
    }
}
