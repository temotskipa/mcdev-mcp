import { assertJavaAtLeast, parseJavaMajorVersion } from '../src/utils/java.js';

describe('Java runtime helpers', () => {
  test('parses modern Java version output', () => {
    expect(parseJavaMajorVersion('openjdk version "25.0.1" 2026-10-21')).toBe(25);
    expect(parseJavaMajorVersion('java version "26-ea"')).toBe(26);
  });

  test('parses legacy Java version output', () => {
    expect(parseJavaMajorVersion('java version "1.8.0_402"')).toBe(8);
  });

  test('requires Java 25 or newer', () => {
    expect(() => assertJavaAtLeast('java', 25, 'indexer', 'openjdk version "24.0.2"')).toThrow(/Java 25\+/);
    expect(() => assertJavaAtLeast('java', 25, 'indexer', 'openjdk version "25.0.1"')).not.toThrow();
  });
});
