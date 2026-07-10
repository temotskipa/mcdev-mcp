import { parseJavaContentWithBackend } from '../src/indexer/parser';

describe('Declaration Parser', () => {
  test('parses single-line class declaration', () => {
    const code = `
      package com.example;
      public class Foo extends Bar implements Baz {
      }
    `;
    const result = parseJavaContentWithBackend(code, 'Foo.java', 'regex');
    expect(result?.className).toBe('Foo');
    expect(result?.info.kind).toBe('class');
    expect(result?.info.super).toBe('Bar');
    expect(result?.info.interfaces).toEqual(['Baz']);
  });

  test('parses multi-line interface declaration', () => {
    const code = `
      package net.minecraft.network.chat;
      public interface Component
      extends Message,
      FormattedText {
      }
    `;
    const result = parseJavaContentWithBackend(code, 'Component.java', 'regex');
    expect(result?.className).toBe('Component');
    expect(result?.info.kind).toBe('interface');
    expect(result?.info.interfaces).toEqual(['Message', 'FormattedText']);
  });

  test('parses record declaration', () => {
    const code = `
      package net.minecraft.network.chat;
      public record ChatType(ChatTypeDecoration chat, ChatTypeDecoration narration) {
      }
    `;
    const result = parseJavaContentWithBackend(code, 'ChatType.java', 'regex');
    expect(result?.className).toBe('ChatType');
    expect(result?.info.kind).toBe('record');
  });

  test('parses nested record in class', () => {
    const code = `
      package com.example;
      public class Outer {
        public record Inner(String name) {}
      }
    `;
    const result = parseJavaContentWithBackend(code, 'Outer.java', 'regex');
    expect(result?.className).toBe('Outer');
    expect(result?.info.kind).toBe('class');
  });

  test('parses interface with single extends', () => {
    const code = `
      package com.example;
      public interface MyInterface extends BaseInterface {
      }
    `;
    const result = parseJavaContentWithBackend(code, 'MyInterface.java', 'regex');
    expect(result?.className).toBe('MyInterface');
    expect(result?.info.kind).toBe('interface');
    expect(result?.info.super).toBe(null);
    expect(result?.info.interfaces).toEqual(['BaseInterface']);
  });

  test('parses class with multiple implements', () => {
    const code = `
      package com.example;
      public class MyClass implements Runnable, Serializable, Cloneable {
      }
    `;
    const result = parseJavaContentWithBackend(code, 'MyClass.java', 'regex');
    expect(result?.className).toBe('MyClass');
    expect(result?.info.interfaces).toEqual(['Runnable', 'Serializable', 'Cloneable']);
  });

  test('parses enum declaration', () => {
    const code = `
      package com.example;
      public enum Color {
        RED, GREEN, BLUE
      }
    `;
    const result = parseJavaContentWithBackend(code, 'Color.java', 'regex');
    expect(result?.className).toBe('Color');
    expect(result?.info.kind).toBe('enum');
  });

  test('parses abstract class', () => {
    const code = `
      package com.example;
      public abstract class AbstractBase {
      }
    `;
    const result = parseJavaContentWithBackend(code, 'AbstractBase.java', 'regex');
    expect(result?.className).toBe('AbstractBase');
    expect(result?.info.kind).toBe('class');
  });

  test('parses final class', () => {
    const code = `
      package com.example;
      public final class FinalClass {
      }
    `;
    const result = parseJavaContentWithBackend(code, 'FinalClass.java', 'regex');
    expect(result?.className).toBe('FinalClass');
    expect(result?.info.kind).toBe('class');
  });

  test('parses class with generic extends', () => {
    const code = `
      package com.example;
      public class MyList extends ArrayList<String> implements List<String> {
      }
    `;
    const result = parseJavaContentWithBackend(code, 'MyList.java', 'regex');
    expect(result?.className).toBe('MyList');
    expect(result?.info.super).toBe('ArrayList');
    expect(result?.info.interfaces).toEqual(['List']);
  });
});
