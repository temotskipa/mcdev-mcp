package dev.mcdevmcp.analysis.index;

import dev.mcdevmcp.storage.AtomicH2Database;
import dev.mcdevmcp.storage.SymbolSchema;
import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.FabricApiVersion;

import javax.lang.model.element.Modifier;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

//noinspection SqlNoDataSourceInspection,SqlResolve
final class SymbolIndexWriter {
    private final AtomicH2Database databases;

    SymbolIndexWriter() {
        this(new AtomicH2Database());
    }

    SymbolIndexWriter(AtomicH2Database databases) {
        this.databases = Objects.requireNonNull(databases, "databases");
    }

    IndexCounts write(IndexRequest request, ParsedIndex index, String remappedJarSha256, Instant builtAt) throws Exception {
        List<IndexedPackage> packages = packages(index.types());
        IndexCounts counts = counts(packages, index.types());
        List<String> expectedBinaryNames = index.types().stream().map(ParsedType::binaryName).toList();
        return databases.rebuild(request.outputDatabase(), AtomicH2Database.WRITE_LOCK_TIMEOUT, connection -> {
            request.cancellation().throwIfCancelled();
            SymbolSchema.create(connection, request.minecraftVersion(), request.sourceRoots().getFirst().path(), remappedJarSha256, builtAt);
            insertPackages(connection, packages);
            insertTypesAndMembers(connection, packages, index.types(), request);
            SymbolSchema.createIndexes(connection);
            return counts;
        }, connection -> {
            SymbolSchema.validate(connection);
            validateCounts(connection, counts);
            validateIdentities(connection, packages, expectedBinaryNames);
        });
    }

    private static List<IndexedPackage> packages(List<ParsedType> types) {
        SortedSet<PackageIdentity> identities = new TreeSet<>();
        types.stream().map(PackageIdentity::new).forEach(identities::add);
        List<IndexedPackage> packages = new ArrayList<>(identities.size());
        long id = 1;
        for (PackageIdentity identity : identities) {
            packages.add(new IndexedPackage(id++, identity.namespace(), identity.fabricApiVersion(), identity.packageName()));
        }
        return List.copyOf(packages);
    }

    private static IndexCounts counts(List<IndexedPackage> packages, List<ParsedType> types) {
        int fields = types.stream().mapToInt(type -> type.fields().size()).sum();
        int methods = types.stream().mapToInt(type -> type.methods().size()).sum();
        int parameters = types.stream().flatMap(type -> type.methods().stream()).mapToInt(method -> method.parameters().size()).sum();
        return new IndexCounts(packages.size(), types.size(), fields, methods, parameters);
    }

    private static void insertPackages(Connection connection, List<IndexedPackage> packages) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql("INSERT INTO packages(id, source_namespace, fabric_api_version, name) VALUES (?, ?, ?, ?)"))) {
            for (IndexedPackage indexedPackage : packages) {
                statement.setLong(1, indexedPackage.id());
                statement.setString(2, indexedPackage.namespace().wireName());
                setOptional(statement, 3, indexedPackage.fabricApiVersion().map(FabricApiVersion::value).orElse(null));
                statement.setString(4, indexedPackage.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertTypesAndMembers(Connection connection, List<IndexedPackage> packages, List<ParsedType> types, IndexRequest request) throws Exception {
        Map<PackageIdentity, Long> packageIds = new HashMap<>();
        packages.forEach(indexedPackage -> packageIds.put(new PackageIdentity(indexedPackage.namespace(), indexedPackage.fabricApiVersion(), indexedPackage.name()), indexedPackage.id()));
        long fieldId = 1;
        long methodId = 1;
        long parameterId = 1;
        try (PreparedStatement typeStatement = connection.prepareStatement(sql("INSERT INTO types(id, package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, superclass_binary_name, source_path, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
             PreparedStatement interfaceStatement = connection.prepareStatement(sql("INSERT INTO type_interfaces(type_id, ordinal, interface_binary_name) VALUES (?, ?, ?)"));
             PreparedStatement fieldStatement = connection.prepareStatement(sql("INSERT INTO fields(id, type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
             PreparedStatement methodStatement = connection.prepareStatement(sql("INSERT INTO methods(id, type_id, ordinal, name, descriptor, return_type, modifiers, constructor, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
             PreparedStatement parameterStatement = connection.prepareStatement(sql("INSERT INTO parameters(id, method_id, ordinal, name, type, varargs, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))) {
            for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
                request.cancellation().throwIfCancelled();
                ParsedType type = types.get(typeIndex);
                long typeId = typeIndex + 1L;
                Long packageId = packageIds.get(new PackageIdentity(type));
                if (packageId == null) {
                    throw new SQLException("No deterministic package ID for " + type.binaryName());
                }
                typeStatement.setLong(1, typeId);
                typeStatement.setLong(2, packageId);
                typeStatement.setString(3, type.sourceRoot().namespace().wireName());
                setOptional(typeStatement, 4, type.sourceRoot().fabricApiVersion().map(FabricApiVersion::value).orElse(null));
                typeStatement.setString(5, type.binaryName());
                typeStatement.setString(6, type.simpleName());
                typeStatement.setString(7, ElementKindCodec.wireName(type.kind()));
                setOptional(typeStatement, 8, type.superclass().map(DescriptorNames::binaryName).orElse(null));
                typeStatement.setString(9, portable(type.sourcePath()));
                setRange(typeStatement, 10, type.range());
                typeStatement.addBatch();
                for (int interfaceIndex = 0; interfaceIndex < type.interfaces().size(); interfaceIndex++) {
                    interfaceStatement.setLong(1, typeId);
                    interfaceStatement.setInt(2, interfaceIndex);
                    interfaceStatement.setString(3, DescriptorNames.binaryName(type.interfaces().get(interfaceIndex)));
                    interfaceStatement.addBatch();
                }
                for (ParsedField field : type.fields()) {
                    fieldStatement.setLong(1, fieldId++);
                    fieldStatement.setLong(2, typeId);
                    fieldStatement.setInt(3, field.ordinal());
                    fieldStatement.setString(4, field.name());
                    fieldStatement.setString(5, field.type());
                    fieldStatement.setString(6, modifiers(field.modifiers()));
                    setRange(fieldStatement, 7, field.range());
                    fieldStatement.addBatch();
                }
                for (ParsedMethod method : type.methods()) {
                    long currentMethodId = methodId++;
                    methodStatement.setLong(1, currentMethodId);
                    methodStatement.setLong(2, typeId);
                    methodStatement.setInt(3, method.ordinal());
                    methodStatement.setString(4, method.name());
                    methodStatement.setString(5, method.descriptor().descriptorString());
                    setOptional(methodStatement, 6, method.returnType().orElse(null));
                    methodStatement.setString(7, modifiers(method.modifiers()));
                    methodStatement.setBoolean(8, method.constructor());
                    setRange(methodStatement, 9, method.range());
                    methodStatement.addBatch();
                    for (ParsedParameter parameter : method.parameters()) {
                        parameterStatement.setLong(1, parameterId++);
                        parameterStatement.setLong(2, currentMethodId);
                        parameterStatement.setInt(3, parameter.ordinal());
                        parameterStatement.setString(4, parameter.name());
                        parameterStatement.setString(5, parameter.type());
                        parameterStatement.setBoolean(6, parameter.varargs());
                        setRange(parameterStatement, 7, parameter.range());
                        parameterStatement.addBatch();
                    }
                }
            }
            typeStatement.executeBatch();
            interfaceStatement.executeBatch();
            fieldStatement.executeBatch();
            methodStatement.executeBatch();
            parameterStatement.executeBatch();
        }
    }

    private static void validateCounts(Connection connection, IndexCounts expected) throws SQLException {
        Map<String, Integer> counts = Map.of("packages", expected.packages(), "types", expected.types(), "fields", expected.fields(), "methods", expected.methods(), "parameters", expected.parameters());
        for (var entry : counts.entrySet()) {
            try (Statement statement = connection.createStatement();
                 ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM " + entry.getKey())) {
                if (!results.next() || results.getInt(1) != entry.getValue() || results.next()) {
                    throw new SQLException("Unexpected " + entry.getKey() + " row count; expected " + entry.getValue());
                }
            }
        }
    }

    private static void validateIdentities(Connection connection, List<IndexedPackage> packages, List<String> binaryNames) throws SQLException {
        List<String> actualPackages = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT id, source_namespace, fabric_api_version, name FROM packages ORDER BY id"))) {
            while (results.next()) {
                actualPackages.add(results.getLong(1) + "|" + results.getString(2) + "|" + results.getString(3) + "|" + results.getString(4));
            }
        }
        List<String> expectedPackages = packages.stream().map(indexedPackage -> indexedPackage.id() + "|" + indexedPackage.namespace().wireName() + "|" + indexedPackage.fabricApiVersion().map(FabricApiVersion::value).orElse(null) + "|" + indexedPackage.name()).toList();
        if (!expectedPackages.equals(actualPackages)) {
            throw new SQLException("Deterministic package identities do not match: expected " + expectedPackages + ", found " + actualPackages);
        }
        List<String> actualBinaryNames = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT binary_name FROM types ORDER BY id"))) {
            while (results.next()) {
                actualBinaryNames.add(results.getString(1));
            }
        }
        if (!binaryNames.equals(actualBinaryNames)) {
            throw new SQLException("Deterministic type identities do not match: expected " + binaryNames + ", found " + actualBinaryNames);
        }
    }

    private static void setRange(PreparedStatement statement, int firstColumn, SourceRange range) throws SQLException {
        statement.setInt(firstColumn, range.startOffset());
        statement.setInt(firstColumn + 1, range.endOffset());
        statement.setInt(firstColumn + 2, range.startLine());
        statement.setInt(firstColumn + 3, range.endLine());
    }

    private static void setOptional(PreparedStatement statement, int column, String value) throws SQLException {
        if (value != null) {
            statement.setString(column, value);
        }
        else {
            statement.setNull(column, Types.VARCHAR);
        }
    }

    private static String modifiers(Set<Modifier> modifiers) {
        return Arrays.stream(Modifier.values()).filter(modifiers::contains).map(modifier -> modifier.name().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining(","));
    }

    private static String portable(Path path) {
        StringBuilder value = new StringBuilder();
        for (Path part : path) {
            if (!value.isEmpty()) {
                value.append('/');
            }
            value.append(part);
        }
        return value.toString();
    }

    private static String sql(String statement) {
        return statement;
    }
}
