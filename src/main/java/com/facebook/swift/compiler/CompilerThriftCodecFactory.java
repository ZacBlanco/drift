/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.swift.compiler;

import com.facebook.swift.ThriftCodec;
import com.facebook.swift.ThriftCodecManager;
import com.facebook.swift.ThriftProtocolFieldType;
import com.facebook.swift.compiler.byteCode.CaseStatement;
import com.facebook.swift.compiler.byteCode.ClassDefinition;
import com.facebook.swift.compiler.byteCode.FieldDefinition;
import com.facebook.swift.compiler.byteCode.MethodDefinition;
import com.facebook.swift.compiler.byteCode.NamedParameterDefinition;
import com.facebook.swift.compiler.byteCode.ParameterizedType;
import com.facebook.swift.internal.ThriftCodecFactory;
import com.facebook.swift.metadata.ThriftConstructorInjection;
import com.facebook.swift.metadata.ThriftExtraction;
import com.facebook.swift.metadata.ThriftFieldExtractor;
import com.facebook.swift.metadata.ThriftFieldInjection;
import com.facebook.swift.metadata.ThriftFieldMetadata;
import com.facebook.swift.metadata.ThriftInjection;
import com.facebook.swift.metadata.ThriftMethodExtractor;
import com.facebook.swift.metadata.ThriftMethodInjection;
import com.facebook.swift.metadata.ThriftParameterInjection;
import com.facebook.swift.metadata.ThriftStructMetadata;
import com.facebook.swift.metadata.ThriftType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.facebook.swift.ThriftProtocolFieldType.*;
import static com.facebook.swift.compiler.byteCode.Access.BRIDGE;
import static com.facebook.swift.compiler.byteCode.Access.FINAL;
import static com.facebook.swift.compiler.byteCode.Access.PRIVATE;
import static com.facebook.swift.compiler.byteCode.Access.PUBLIC;
import static com.facebook.swift.compiler.byteCode.Access.SUPER;
import static com.facebook.swift.compiler.byteCode.Access.SYNTHETIC;
import static com.facebook.swift.compiler.byteCode.Access.a;
import static com.facebook.swift.compiler.byteCode.CaseStatement.caseStatement;
import static com.facebook.swift.compiler.byteCode.NamedParameterDefinition.arg;
import static com.facebook.swift.compiler.byteCode.ParameterizedType.type;

public class CompilerThriftCodecFactory implements ThriftCodecFactory {
  private static final String PACKAGE = "$thrift";

  private final DynamicClassLoader classLoader;
  private final boolean debug;

  public CompilerThriftCodecFactory() {
    classLoader = new DynamicClassLoader();
    debug = false;
  }

  public CompilerThriftCodecFactory(DynamicClassLoader classLoader, boolean debug) {
    this.classLoader = classLoader;
    this.debug = debug;
  }

  @Override
  public <T> ThriftCodec<T> generateThriftTypeCodec(
      ThriftCodecManager codecManager,
      ThriftStructMetadata<T> metadata
  ) {
    List<Class<?>> parameterTypes = new ArrayList<>();
    List<Object> parameters = new ArrayList<>();

    ThriftType thriftType = ThriftType.struct(metadata);
    parameterTypes.add(ThriftType.class);
    parameters.add(thriftType);

    // get codecs for al fields
    Map<Short, ThriftCodec<?>> fieldCodecs = new TreeMap<>();
    for (ThriftFieldMetadata field : metadata.getFields()) {
      if (needsCodec(field)) {
        fieldCodecs.put(field.getId(), codecManager.getCodec(field.getType()));
      }
    }
    for (ThriftCodec<?> codec : fieldCodecs.values()) {
      parameterTypes.add(ThriftCodec.class);
      parameters.add(codec);
    }

    // generate the class
    Class<?> codecClass = generateClass(metadata);

    try {
      Constructor<?> constructor = codecClass.getConstructor(
          parameterTypes.toArray(new Class[parameterTypes.size()])
      );

      return (ThriftCodec<T>) constructor.newInstance(
          parameters.toArray(new Object[parameters.size()])
      );
    } catch (Exception e) {
      throw new IllegalStateException("Generated class is invalid", e);
    }
  }

  private Class<?> generateClass(ThriftStructMetadata<?> metadata) {
    ParameterizedType structType = type(metadata.getStructClass());
    ParameterizedType codecType = toCodecType(metadata);

    ClassDefinition classDefinition = new ClassDefinition(
        a(PUBLIC, SUPER),
        codecType.getClassName(),
        type(Object.class),
        type(ThriftCodec.class, structType)
    );

    // private ThriftType type;
    FieldDefinition typeField;
    {
      typeField = new FieldDefinition(a(PRIVATE, FINAL), "type", type(ThriftType.class));
      classDefinition.addField(typeField);
    }

    Map<Short, FieldDefinition> codecFields = new TreeMap<>();
    List<NamedParameterDefinition> constructorParams = new ArrayList<>();
    for (ThriftFieldMetadata fieldMetadata : metadata.getFields()) {
      if (needsCodec(fieldMetadata)) {
        ParameterizedType fieldType = type(
            ThriftCodec.class,
            toParameterizedType(fieldMetadata.getType())
        );
        String fieldName = fieldMetadata.getName() + "Codec";

        FieldDefinition codecField = new FieldDefinition(a(PRIVATE, FINAL), fieldName, fieldType);
        classDefinition.addField(codecField);
        codecFields.put(fieldMetadata.getId(), codecField);

        constructorParams.add(arg(fieldName, fieldType));
      }
    }

    // default constructor
    {
      constructorParams.add(0, arg("type", ThriftType.class));
      MethodDefinition constructor = new MethodDefinition(
          a(PUBLIC),
          "<init>",
          type(void.class),
          constructorParams
      );

      // invoke super
      constructor.loadThis().invokeConstructor(type(Object.class));

      // this.type = type;
      constructor.loadThis()
          .loadVariable("type")
          .putField(codecType, typeField);

      // this.fooCodec = fooCodec;
      for (FieldDefinition fieldDefinition : codecFields.values()) {
        constructor.loadThis()
            .loadVariable(fieldDefinition.getName())
            .putField(codecType, fieldDefinition);
      }

      // return; (implicit)
      constructor.ret();

      classDefinition.addMethod(constructor);
    }

    // public ThriftType getType()
    {
      classDefinition.addMethod(
          new MethodDefinition(a(PUBLIC), "getType", type(ThriftType.class))
              .loadThis()
              .getField(codecType, typeField)
              .retObject()
      );
    }

    // public Struct read(TProtocolReader protocol) throws Exception
    {
      MethodDefinition read = new MethodDefinition(
          a(PUBLIC),
          "read",
          structType,
          arg("protocol", TProtocolReader.class)
      ).addException(Exception.class);

      // declare and init local variables here
      for (ThriftFieldMetadata field : metadata.getFields()) {
        read.addInitializedLocalVariable(
            toParameterizedType(field.getType()),
            "f_" + field.getName()
        );
      }

      // protocol.readStructBegin();
      read.loadVariable("protocol").invokeVirtual(
          TProtocolReader.class,
          "readStructBegin",
          void.class
      );

      // while (protocol.nextField())
      read.visitLabel("while-begin");
      read.loadVariable("protocol").invokeVirtual(
          TProtocolReader.class,
          "nextField",
          boolean.class
      );
      read.ifNotGoto("while-end");

      // switch (protocol.getFieldId())
      read.loadVariable("protocol").invokeVirtual(TProtocolReader.class, "getFieldId", short.class);
      List<CaseStatement> cases = new ArrayList<>();
      for (ThriftFieldMetadata field : metadata.getFields()) {
        cases.add(caseStatement(field.getId(), field.getName() + "-field"));
      }
      read.switchStatement("default", cases);

      for (ThriftFieldMetadata field : metadata.getFields()) {
        // case field.id:
        read.visitLabel(field.getName() + "-field");

        // read value from protocol
        switch (field.getType().getProtocolType()) {
          case BOOL:
            read.loadVariable("protocol").invokeVirtual(
                TProtocolReader.class,
                "readBoolField",
                boolean.class
            );
            break;
          case BYTE:
            read.loadVariable("protocol").invokeVirtual(
                TProtocolReader.class,
                "readByteField",
                byte.class
            );
            break;
          case DOUBLE:
            read.loadVariable("protocol").invokeVirtual(
                TProtocolReader.class,
                "readDoubleField",
                double.class
            );
            break;
          case I16:
            read.loadVariable("protocol").invokeVirtual(
                TProtocolReader.class,
                "readI16Field",
                short.class
            );
            break;
          case I32:
            read.loadVariable("protocol").invokeVirtual(
                TProtocolReader.class,
                "readI32Field",
                int.class
            );
            break;
          case I64:
            read.loadVariable("protocol").invokeVirtual(
                TProtocolReader.class,
                "readI64Field",
                long.class
            );
            break;
          case STRING:
            read.loadVariable("protocol").invokeVirtual(
                TProtocolReader.class,
                "readStringField",
                String.class
            );
            break;
          case STRUCT: {
            FieldDefinition fieldDefinition = codecFields.get(field.getId());

            read.loadVariable("protocol")
                .loadThis().getField(codecType, fieldDefinition)
                .invokeVirtual(
                    type(TProtocolReader.class),
                    "readStructField",
                    type(Object.class),
                    type(ThriftCodec.class)
                )
                .checkCast(toParameterizedType(field.getType()));
            break;
          }
          case SET: {
            FieldDefinition fieldDefinition = codecFields.get(field.getId());

            read.loadVariable("protocol")
                .loadThis().getField(codecType, fieldDefinition)
                .invokeVirtual(
                    type(TProtocolReader.class),
                    "readSetField",
                    type(Set.class),
                    type(ThriftCodec.class)
                );
            break;
          }
          case LIST: {
            FieldDefinition fieldDefinition = codecFields.get(field.getId());

            read.loadVariable("protocol")
                .loadThis().getField(codecType, fieldDefinition)
                .invokeVirtual(
                    type(TProtocolReader.class),
                    "readListField",
                    type(List.class),
                    type(ThriftCodec.class)
                );
            break;
          }
          case MAP: {
            FieldDefinition fieldDefinition = codecFields.get(field.getId());

            read.loadVariable("protocol")
                .loadThis().getField(codecType, fieldDefinition)
                .invokeVirtual(
                    type(TProtocolReader.class),
                    "readMapField",
                    type(Map.class),
                    type(ThriftCodec.class)
                );
            break;
          }
          default:
            throw new IllegalArgumentException(
                "Unsupported field type " + field.getType()
                    .getProtocolType()
            );
        }

        // store protocol value
        read.storeVariable("f_" + field.getName());

        // go back to top of loop
        read.gotoLabel("while-begin");
      }

      // default:
      read.visitLabel("default")
          .loadVariable("protocol")
          .invokeVirtual(TProtocolReader.class, "skipFieldData", void.class)
          .gotoLabel("while-begin");

      // end of while loop
      read.visitLabel("while-end");

      // protocol.readStructEnd();
      read.loadVariable("protocol")
          .invokeVirtual(TProtocolReader.class, "readStructEnd", void.class);

      // == BUILD ==

      read.addLocalVariable(structType, "instance");

      // create the new instance (or builder)
      if (metadata.getBuilderClass() == null) {
        read.newObject(structType).dup();
      } else {
        read.newObject(metadata.getBuilderClass()).dup();
      }

      // invoke constructor
      ThriftConstructorInjection constructor = metadata.getConstructor();
      // push parameters on stack
      for (ThriftParameterInjection parameterInjection : constructor.getParameters()) {
        read.loadVariable("f_" + parameterInjection.getName());
      }
      // invoke constructor
      read.invokeConstructor(constructor.getConstructor())
          .storeVariable("instance");

      // inject fields
      for (ThriftFieldMetadata field : metadata.getFields()) {
        for (ThriftInjection injection : field.getInjections()) {
          if (injection instanceof ThriftFieldInjection) {
            ThriftFieldInjection fieldInjection = (ThriftFieldInjection) injection;
            read.loadVariable("instance")
                .loadVariable("f_" + field.getName())
                .putField(fieldInjection.getField());
          }
        }
      }

      // inject methods
      for (ThriftMethodInjection methodInjection : metadata.getMethodInjections()) {
        read.loadVariable("instance");

        // push parameters on stack
        for (ThriftParameterInjection parameterInjection : methodInjection.getParameters()) {
          read.loadVariable("f_" + parameterInjection.getName());
        }

        // invoke the method
        read.invokeVirtual(methodInjection.getMethod());
      }

      // invoke factory method if present
      ThriftMethodInjection builderMethod = metadata.getBuilderMethod();
      if (builderMethod != null) {
        read.loadVariable("instance");

        // push parameters on stack
        for (ThriftParameterInjection parameterInjection : builderMethod.getParameters()) {
          read.loadVariable("f_" + parameterInjection.getName());
        }

        // invoke the method
        read.invokeVirtual(builderMethod.getMethod())
            .storeVariable("instance");
      }

      read.loadVariable("instance")
          .retObject();

      classDefinition.addMethod(read);
    }

    // public void write(Struct struct, TProtocolWriter protocol) throws Exception
    {
      MethodDefinition write = new MethodDefinition(
          a(PUBLIC), "write", null, arg(
          "struct",
          structType
      ), arg("protocol", TProtocolWriter.class)
      );
      classDefinition.addMethod(write);

      write.loadVariable("protocol")
          .loadConstant(metadata.getStructName())
          .invokeVirtual(TProtocolWriter.class, "writeStructBegin", void.class, String.class);

      // field extraction
      for (ThriftFieldMetadata field : metadata.getFields()) {
        write.loadVariable("protocol")
            .loadConstant(field.getName())
            .loadConstant(field.getId())
            .loadVariable("struct");

        ThriftExtraction extraction = field.getExtraction();
        if (extraction instanceof ThriftFieldExtractor) {
          ThriftFieldExtractor fieldExtractor = (ThriftFieldExtractor) extraction;
          write.getField( fieldExtractor.getField());
        } else if (extraction instanceof ThriftMethodExtractor) {
          ThriftMethodExtractor methodExtractor = (ThriftMethodExtractor) extraction;
          write.invokeVirtual(methodExtractor.getMethod());
        }

        switch (field.getType().getProtocolType()) {
          case BOOL:
            write.invokeVirtual(
                TProtocolWriter.class,
                "writeBoolField",
                void.class,
                String.class,
                short.class,
                boolean.class
            );
            break;
          case BYTE:
            write.invokeVirtual(
                TProtocolWriter.class,
                "writeByteField",
                void.class,
                String.class,
                short.class,
                byte.class
            );
            break;
          case DOUBLE:
            write.invokeVirtual(
                TProtocolWriter.class,
                "writeDoubleField",
                void.class,
                String.class,
                short.class,
                double.class
            );
            break;
          case I16:
            write.invokeVirtual(
                TProtocolWriter.class,
                "writeI16Field",
                void.class,
                String.class,
                short.class,
                short.class
            );
            break;
          case I32:
            write.invokeVirtual(
                TProtocolWriter.class,
                "writeI32Field",
                void.class,
                String.class,
                short.class,
                int.class
            );
            break;
          case I64:
            write.invokeVirtual(
                TProtocolWriter.class,
                "writeI64Field",
                void.class,
                String.class,
                short.class,
                long.class
            );
            break;
          case STRING:
            write.invokeVirtual(
                TProtocolWriter.class,
                "writeStringField",
                void.class,
                String.class,
                short.class,
                String.class
            );
            break;
          case STRUCT: {
            FieldDefinition codecField = codecFields.get(field.getId());

            // push ThriftTypeCodec for this field
            write.loadThis().getField(codecType, codecField);

            // swap the codec and value on the stack
            write.swap();

            // protocol.writeStructField("aStruct", 42, this.aStructCodec, aStruct);
            write.invokeVirtual(
                type(TProtocolWriter.class),
                "writeStructField",
                type(void.class),
                type(String.class),
                type(short.class),
                type(ThriftCodec.class),
                type(Object.class)
            );
            break;
          }
          case SET: {
            FieldDefinition codecField = codecFields.get(field.getId());

            // push ThriftTypeCodec for this field
            write.loadThis().getField(codecType, codecField);

            // swap the codec and value on the stack
            write.swap();

            // protocol.writeStructField("aStruct", 42, this.aStructCodec, aStruct);
            write.invokeVirtual(
                type(TProtocolWriter.class),
                "writeSetField",
                type(void.class),
                type(String.class),
                type(short.class),
                type(ThriftCodec.class),
                type(Set.class)
            );
            break;
          }
          case LIST: {
            FieldDefinition codecField = codecFields.get(field.getId());

            // push ThriftTypeCodec for this field
            write.loadThis().getField(codecType, codecField);

            // swap the codec and value on the stack
            write.swap();

            // protocol.writeStructField("aStruct", 42, this.aStructCodec, aStruct);
            write.invokeVirtual(
                type(TProtocolWriter.class),
                "writeListField",
                type(void.class),
                type(String.class),
                type(short.class),
                type(ThriftCodec.class),
                type(List.class)
            );
            break;
          }
          case MAP: {
            FieldDefinition codecField = codecFields.get(field.getId());

            // push ThriftTypeCodec for this field
            write.loadThis().getField(codecType, codecField);

            // swap the codec and value on the stack
            write.swap();

            // protocol.writeStructField("aStruct", 42, this.aStructCodec, aStruct);
            write.invokeVirtual(
                type(TProtocolWriter.class),
                "writeMapField",
                type(void.class),
                type(String.class),
                type(short.class),
                type(ThriftCodec.class),
                type(Map.class)
            );
            break;
          }
          default:
            throw new IllegalArgumentException(
                "Unsupported field type " + field.getType()
                    .getProtocolType()
            );
        }

      }

      write.loadVariable("protocol")
          .invokeVirtual(TProtocolWriter.class, "writeStructEnd", void.class);

      write.ret();
    }

    // public synthetic bridge Object read(TProtocolReader protocol) throws Exception
    {
      classDefinition.addMethod(
          new MethodDefinition(
              a(PUBLIC, BRIDGE, SYNTHETIC), "read", type(Object.class), arg(
              "protocol",
              TProtocolReader.class
          )
          )
              .addException(Exception.class)
              .loadThis()
              .loadVariable("protocol")
              .invokeVirtual(codecType, "read", structType, type(TProtocolReader.class))
              .retObject()
      );
    }

    // public synthetic bridge void write(Object struct, TProtocolWriter protocol) throws Exception
    {
      classDefinition.addMethod(
          new MethodDefinition(
              a(PUBLIC, BRIDGE, SYNTHETIC), "write", null, arg(
              "struct",
              Object.class
          ), arg("protocol", TProtocolWriter.class)
          )
              .addException(Exception.class)
              .loadThis()
              .loadVariable("struct", structType)
              .loadVariable("protocol")
              .invokeVirtual(
                  codecType,
                  "write",
                  type(void.class),
                  structType,
                  type(TProtocolWriter.class)
              )
              .ret()
      );
    }

    ClassNode classNode = classDefinition.getClassNode();

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    classNode.accept(cw);
    byte[] byteCode = cw.toByteArray();

    if (debug) {
      ClassReader reader = new ClassReader(byteCode);
      CheckClassAdapter.verify(reader, classLoader, true, new PrintWriter(System.out));
    }
    Class<?> codecClass = classLoader.defineClass(
        codecType.getClassName().replace('/', '.'),
        byteCode
    );

    return codecClass;
  }

  private boolean needsCodec(ThriftFieldMetadata fieldMetadata) {
    ThriftProtocolFieldType protocolType = fieldMetadata.getType().getProtocolType();
    return protocolType == STRUCT ||
        protocolType == SET ||
        protocolType == LIST ||
        protocolType == MAP;
  }

  private ParameterizedType toCodecType(ThriftStructMetadata<?> metadata) {
    return type(PACKAGE + "/" + type(metadata.getStructClass()).getClassName() + "Codec");
  }

  public static ParameterizedType toParameterizedType(ThriftType type) {
    switch (type.getProtocolType()) {
      case BOOL:
        return type(boolean.class);
      case BYTE:
        return type(byte.class);
      case DOUBLE:
        return type(double.class);
      case I16:
        return type(short.class);
      case I32:
        return type(int.class);
      case I64:
        return type(long.class);
      case STRING:
        return type(String.class);
      case STRUCT:
        return type(type.getStructMetadata().getStructClass());
      case MAP:
        return type(
            Map.class,
            toParameterizedType(type.getKeyType()),
            toParameterizedType(type.getValueType())
        );
      case SET:
        return type(Set.class, toParameterizedType(type.getValueType()));
      case LIST:
        return type(List.class, toParameterizedType(type.getValueType()));
      case ENUM:
        throw new UnsupportedOperationException("Enums are currently not supported");
      default:
        throw new IllegalArgumentException("Unsupported thrift field type " + type);
    }
  }

}