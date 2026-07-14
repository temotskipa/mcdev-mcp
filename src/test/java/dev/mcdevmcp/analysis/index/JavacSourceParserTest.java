package dev.mcdevmcp.analysis.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JavacSourceParserTest {
    @TempDir
    Path temporaryDirectory;
    
    private static long typeId(List<String> dump, String binaryName) {
        String row = dump.stream().filter(candidate -> candidate.startsWith("types|") && candidate.contains("|" + binaryName + "|")).findFirst().orElseThrow();
        return Long.parseLong(row.split("\\|", -1)[1]);
    }
    
    private static boolean memberOf(String row, String table, long typeId) {
        return row.startsWith(table + "|") && row.split("\\|", -1)[2].equals(Long.toString(typeId));
    }
    
    private static void assertRecordRange(List<String> dump, String table, String name, String source, String declaration) {
        String row = dump.stream().filter(candidate -> candidate.startsWith(table + "|") && candidate.split("\\|", -1)[4].equals(name)).findFirst().orElseThrow();
        String[] columns = row.split("\\|", -1);
        int start = source.indexOf(declaration);
        int end = start + declaration.length();
        int startLine = 1 + (int) source.substring(0, start).chars().filter(character -> character == '\n').count();
        int endLine = startLine + (int) declaration.chars().filter(character -> character == '\n').count();
        assertEquals(start, Integer.parseInt(columns[7]), row);
        assertEquals(end, Integer.parseInt(columns[8]), row);
        assertEquals(startLine, Integer.parseInt(columns[9]), row);
        assertEquals(endLine, Integer.parseInt(columns[10]), row);
        assertEquals(declaration, source.substring(Integer.parseInt(columns[7]), Integer.parseInt(columns[8])));
    }
    
    @Test
    void indexesEveryTopLevelDeclarationAndOnlySourceDeclaredDirectMembers() throws Exception {
        Path sources = IndexerTestSupport.copyFixture("main", temporaryDirectory.resolve("sources"));
        Path jar = IndexerTestSupport.fixtureCatalog(temporaryDirectory.resolve("remapped.jar"));
        Path dependency = IndexerTestSupport.fixtureDependency(temporaryDirectory.resolve("dependency.jar"));
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        
        IndexRequest request = IndexerTestSupport.request(List.of(new SourceRoot(dev.mcdevmcp.storage.model.SourceNamespace.MINECRAFT, Optional.empty(), sources)), jar, List.of(dependency), database, 1);
        IndexSummary summary = new SourceIndexer().build(request);
        List<String> dump = IndexerTestSupport.dump(database);
        
        assertEquals(8, summary.types());
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.FeatureSet|FeatureSet|class|java.util.ArrayList|")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("type_interfaces|") && row.endsWith("|java.lang.Runnable")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.Child|Child|class|index.fixture.FeatureSet|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.NestedChild|NestedChild|class|index.fixture.FeatureSet$Nested|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.SourceBase|SourceBase|class|java.lang.Object|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.Pair|Pair|record|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.Marker|Marker|annotation|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.Shade|Shade|enum|")));
        assertFalse(dump.stream().anyMatch(row -> row.startsWith("types|") && row.split("\\|", -1)[5].equals("index.fixture.FeatureSet$Nested")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|hidden|")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|hiddenMethod|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|values|java.util.List<? super T[]>|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|external|dependency.External|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|FIRST|int|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|SECOND|int|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|transform|(Ljava/lang/CharSequence;[Ljava/lang/String;)Ljava/util/List;|java.util.List<? extends U>|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|rest|java.lang.String[]|true|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|<init>|(Ljava/lang/Object;Ljava/lang/Object;)V|null|")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|FeatureSet|()V|")), "constructors use <init>, not the source type name");
        assertFalse(dump.stream().anyMatch(row -> row.contains("|values|()[Lindex/fixture/Shade;|") || row.contains("|valueOf|(Ljava/lang/String;)Lindex/fixture/Shade;|")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|left|()Ljava/lang/Object;|") || row.contains("|right|()Ljava/lang/Object;|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|left|T|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|right|T|")));
        
        long sourceBaseId = typeId(dump, "index.fixture.SourceBase");
        long defaultsId = typeId(dump, "index.fixture.Defaults");
        long markerId = typeId(dump, "index.fixture.Marker");
        long shadeId = typeId(dump, "index.fixture.Shade");
        assertFalse(dump.stream().anyMatch(row -> row.startsWith("methods|") && row.split("\\|", -1)[2].equals(Long.toString(sourceBaseId))), "default constructors are compiler-generated");
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "fields", defaultsId) && row.contains("|CONSTANT|int|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "methods", defaultsId) && row.contains("|value|()I|int|public,default|")));
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "methods", markerId) && row.contains("|value|()Ljava/lang/String;|java.lang.String|public,abstract|")));
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "fields", shadeId) && row.contains("|RED|index.fixture.Shade|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "fields", shadeId) && row.contains("|BLUE|index.fixture.Shade|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("types|") && row.contains("|index/fixture/FeatureSet.java|")));
    }
    
    @Test
    void preservesUtf16ExclusiveOffsetsAndEndLines() throws Exception {
        Path sources = temporaryDirectory.resolve("unicode");
        java.nio.file.Files.createDirectories(sources.resolve("unicode"));
        String source = "package unicode;\npublic class Ranges {\n    String value = \"\uD83D\uDE00\";\n    void first() {\n    }\n    void second() {}\n}\n";
        java.nio.file.Files.writeString(sources.resolve("unicode/Ranges.java"), source, java.nio.charset.StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), java.util.Map.of());
        Path database = temporaryDirectory.resolve("ranges.mv.db");
        
        new SourceIndexer().build(IndexerTestSupport.request(sources, jar, database, 1));
        List<String> dump = IndexerTestSupport.dump(database);
        
        String type = dump.stream().filter(row -> row.startsWith("types|")).findFirst().orElseThrow();
        String[] typeColumns = type.split("\\|", -1);
        assertEquals(source.indexOf("public class"), Integer.parseInt(typeColumns[10]));
        assertEquals(source.lastIndexOf('}') + 1, Integer.parseInt(typeColumns[11]));
        assertEquals("2", typeColumns[12]);
        assertEquals("7", typeColumns[13]);
        String first = dump.stream().filter(row -> row.startsWith("methods|") && row.contains("|first|")).findFirst().orElseThrow();
        assertTrue(first.endsWith("|4|5"), first);
        assertEquals(source.length(), source.codePoints().map(Character::charCount).sum());
    }
    
    @Test
    void usesExactCompilerRangesForAnnotatedMultilineRecordComponentsAndCompactParameters() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("record-ranges/ranges"));
        String source = """
                        package ranges;
                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Target;
                        @Target(ElementType.RECORD_COMPONENT)
                        @interface Label { String value(); }
                        public record ExactRecord(
                                @Label("name)") String name,
                                @Label("name,") java.util.List<
                                        String
                                    > values,
                                String nameAgain
                        ) {
                            public ExactRecord {
                            }
                        }
                        """;
        Files.writeString(sources.resolve("ExactRecord.java"), source, StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("record-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("record-ranges.mv.db");
        
        new SourceIndexer().build(IndexerTestSupport.request(sources.getParent(), jar, database, 1));
        List<String> dump = IndexerTestSupport.dump(database);
        
        assertRecordRange(dump, "fields", "name", source, "@Label(\"name)\") String name");
        assertRecordRange(dump, "fields", "values", source, """
                                                            @Label("name,") java.util.List<
                                                                            String
                                                                        > values""");
        assertRecordRange(dump, "fields", "nameAgain", source, "String nameAgain");
        assertRecordRange(dump, "parameters", "name", source, "@Label(\"name)\") String name");
        assertRecordRange(dump, "parameters", "values", source, """
                                                                @Label("name,") java.util.List<
                                                                                String
                                                                            > values""");
        assertRecordRange(dump, "parameters", "nameAgain", source, "String nameAgain");
    }
}
