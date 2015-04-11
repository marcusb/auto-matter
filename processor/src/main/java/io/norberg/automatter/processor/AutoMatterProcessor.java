package io.norberg.automatter.processor;

import com.google.auto.service.AutoService;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import io.norberg.automatter.AutoMatter;
import org.modeshape.common.text.Inflector;

import javax.annotation.Generated;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Collections.reverse;
import static javax.lang.model.element.ElementKind.PACKAGE;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.type.TypeKind.ARRAY;
import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * An annotation processor that takes a value type defined as an interface with getter methods and
 * materializes it, generating a concrete builder and value class.
 */
@AutoService(Processor.class)
public final class AutoMatterProcessor extends AbstractProcessor {

  public static final Set<String> KEYWORDS = ImmutableSet.of(
      "abstract", "continue", "for", "new", "switch", "assert", "default", "if", "package",
      "synchronized", "boolean", "do", "goto", "private", "this", "break", "double", "implements",
      "protected", "throw", "byte", "else", "import", "public", "throws", "case", "enum",
      "instanceof", "return", "transient", "catch", "extends", "int", "short", "try", "char",
      "final", "interface", "static", "void", "class", "finally", "long", "strictfp", "volatile",
      "const", "float", "native", "super", "while");

  private Filer filer;
  private Elements elements;
  private Messager messager;
  public static final Inflector INFLECTOR = new Inflector();

  @Override
  public synchronized void init(final ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    filer = processingEnv.getFiler();
    elements = processingEnv.getElementUtils();
    this.messager = processingEnv.getMessager();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations,
                         final RoundEnvironment env) {
    final Set<? extends Element> elements = env.getElementsAnnotatedWith(AutoMatter.class);
    for (Element element : elements) {
      try {
        writeBuilder(element);
      } catch (IOException e) {
        messager.printMessage(ERROR, e.getMessage());
      } catch (AutoMatterProcessorException e) {
        e.print(messager);
      }
    }
    return false;
  }

  private void writeBuilder(final Element element) throws IOException,
                                                          AutoMatterProcessorException {
    final Descriptor d = new Descriptor(element);

    TypeSpec builder = builder(d);
    JavaFile javaFile = JavaFile.builder(d.packageName, builder)
        .skipJavaLangImports(true)
        .build();
    javaFile.writeTo(filer);
  }

  private TypeSpec builder(final Descriptor d) throws AutoMatterProcessorException {
    Modifier[] modifiers = d.isPublic ? new Modifier[]{PUBLIC, FINAL} : new Modifier[]{FINAL};

    AnnotationSpec generatedAnnotation = AnnotationSpec.builder(Generated.class)
        .addMember("value", "$S", AutoMatterProcessor.class.getName())
        .build();

    TypeSpec.Builder builder = TypeSpec.classBuilder(d.builderSimpleName)
        .addModifiers(modifiers)
        .addAnnotation(generatedAnnotation);

    for (ExecutableElement field : d.fields) {
      builder.addField(FieldSpec.builder(fieldType(field), fieldName(field), PRIVATE).build());
    }

    builder.addMethod(defaultConstructor(d));
    builder.addMethod(copyValueConstructor(d));
    builder.addMethod(copyBuilderConstructor(d));

    for (MethodSpec accessor: accessors(d)) {
      builder.addMethod(accessor);
    }

    if (d.toBuilder) {
      builder.addMethod(toBuilder(d));
    }

    builder.addMethod(build(d));
    builder.addMethod(fromValue(d));
    builder.addMethod(fromBuilder(d));

    builder.addType(valueClass(d));

    return builder.build();
  }

  private MethodSpec defaultConstructor(final Descriptor d) {
    MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
        .addModifiers(PUBLIC);

    for (ExecutableElement field : d.fields) {
      if (isOptional(field) && shouldEnforceNonNull(field)) {
        ClassName type = ClassName.bestGuess(optionalType(field));
        constructor.addStatement("this.$N = $T.$L()", fieldName(field), type, optionalEmptyName(field));
      }
    }

    return constructor.build();
  }

  private MethodSpec copyValueConstructor(final Descriptor d) throws AutoMatterProcessorException {
    ClassName valueClass = ClassName.get(d.packageName, d.targetSimpleName);

    MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
        .addModifiers(PRIVATE)
        .addParameter(valueClass, "v");

    for (ExecutableElement field : d.fields) {
      String fieldName = fieldName(field);
      TypeName fieldType = fieldType(field);

      if (isCollection(field)) {
        final ClassName collectionImplType = collectionImplType(field);
        final TypeName itemType = genericArgument(field, 0);
        constructor.addStatement("$T _$N = v.$N()", fieldType, fieldName, fieldName);
        constructor.addStatement(
            "this.$N = (_$N == null) ? null : new $T<$T>(_$N)",
            fieldName, fieldName, collectionImplType, itemType, fieldName);
      } else if (isMap(field)) {
        final ClassName mapType = ClassName.get(HashMap.class);
        final TypeName keyType = genericArgument(field, 0);
        final TypeName valueType = genericArgument(field, 1);
        constructor.addStatement("$T _$N = v.$N()", fieldType, fieldName, fieldName);
        constructor.addStatement(
            "this.$N = (_$N == null) ? null : new $T<$T, $T>(_$N)",
            fieldName, fieldName, mapType, keyType, valueType, fieldName);
      } else {
        constructor.addStatement("this.$N = v.$N()", fieldName, fieldName);
      }
    }

    return constructor.build();
  }

  private MethodSpec copyBuilderConstructor(final Descriptor d) {
    ClassName builderClass = builderType(d);

    MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
        .addModifiers(PRIVATE)
        .addParameter(builderClass, "v");

    for (ExecutableElement field : d.fields) {
      String fieldName = fieldName(field);

      if (isCollection(field)) {
        final ClassName collectionImplType = collectionImplType(field);
        final TypeName itemType = genericArgument(field, 0);
        constructor.addStatement(
            "this.$N = (v.$N == null) ? null : new $T<$T>(v.$N)",
            fieldName, fieldName, collectionImplType, itemType, fieldName);
      } else if (isMap(field)) {
        final ClassName mapType = ClassName.get(HashMap.class);
        final TypeName keyType = genericArgument(field, 0);
        final TypeName valueType = genericArgument(field, 1);
        constructor.addStatement(
            "this.$N = (v.$N == null) ? null : new $T<$T, $T>(v.$N)",
            fieldName, fieldName, mapType, keyType, valueType, fieldName);
      } else {
        constructor.addStatement("this.$N = v.$N", fieldName, fieldName);
      }
    }

    return constructor.build();
  }

  private Set<MethodSpec> accessors(final Descriptor d) throws AutoMatterProcessorException {
    ImmutableSet.Builder<MethodSpec> result = ImmutableSet.builder();
    for (ExecutableElement field : d.fields) {
      result.add(getter(field));

      if (isOptional(field)) {
        result.add(optionalRawSetter(d, field));
        result.add(optionalSetter(d, field));
      } else if (isCollection(field)) {
        result.add(collectionSetter(d, field));
        result.add(collectionCollectionSetter(d, field));
        result.add(collectionIterableSetter(d, field));
        result.add(collectionIteratorSetter(d, field));
        result.add(collectionVarargSetter(d, field));

        MethodSpec adder = collectionAdder(d, field);
        if (adder != null) {
          result.add(adder);
        }
      } else if (isMap(field)) {
        result.add(mapSetter(d, field));
        for (int i=1; i<=5; i++) {
          result.add(mapSetterPairs(d, field, i));
        }

        MethodSpec putter = mapPutter(d, field);
        if (putter != null) {
          result.add(putter);
        }
      } else {
        result.add(setter(d, field));
      }
    }
    return result.build();
  }

  private MethodSpec getter(final ExecutableElement field) throws AutoMatterProcessorException {
    String fieldName = fieldName(field);

    MethodSpec.Builder getter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .returns(fieldType(field));

    if (isCollection(field) && shouldEnforceNonNull(field)) {
      final ClassName collectionImplType = collectionImplType(field);
      final TypeName itemType = genericArgument(field, 0);
      getter.beginControlFlow("if (this.$N == null)", fieldName)
          .addStatement("this.$N = new $T<$T>()", fieldName, collectionImplType, itemType)
          .endControlFlow();
    } else if (isMap(field) && shouldEnforceNonNull(field)) {
      final ClassName mapType = ClassName.get(HashMap.class);
      final TypeName keyType = genericArgument(field, 0);
      final TypeName valueType = genericArgument(field, 1);
      getter.beginControlFlow("if (this.$N == null)", fieldName)
          .addStatement("this.$N = new $T<$T, $T>()", fieldName, mapType, keyType, valueType)
          .endControlFlow();
    }
    getter.addStatement("return $N", fieldName);

    return getter.build();
  }

  private MethodSpec optionalRawSetter(final Descriptor d, final ExecutableElement field) {
    String fieldName = fieldName(field);
    ClassName type = ClassName.bestGuess(optionalType(field));
    TypeName valueType = collectionGenericArgument(field);

    return MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(valueType, fieldName)
        .returns(builderType(d))
        .addStatement("return $N($T.$N($N))", fieldName, type, optionalMaybeName(field), fieldName)
        .build();
  }

  private MethodSpec optionalSetter(final Descriptor d, final ExecutableElement field) throws AutoMatterProcessorException {
    String fieldName = fieldName(field);
    TypeName valueType = collectionGenericArgument(field);
    ClassName optionalType = ClassName.bestGuess(optionalType(field));
    TypeName parameterType = ParameterizedTypeName.get(optionalType, WildcardTypeName.subtypeOf(valueType));

    MethodSpec.Builder setter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(parameterType, fieldName)
        .returns(builderType(d));

    if (shouldEnforceNonNull(field)) {
      assertNotNull(setter, fieldName);
    }

    setter.addStatement("this.$N = ($T)$N", fieldName, fieldType(field), fieldName);

    return setter.addStatement("return this").build();
  }

  private MethodSpec collectionSetter(final Descriptor d, final ExecutableElement field) {
    String fieldName = fieldName(field);
    ClassName collectionType = collectionRawType(field);
    TypeName itemType = collectionGenericArgument(field);
    WildcardTypeName extendedType = WildcardTypeName.subtypeOf(itemType);

    return MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(ParameterizedTypeName.get(collectionType, extendedType), fieldName)
        .returns(builderType(d))
        .addStatement("return $N((Collection<$T>) $N)", fieldName, extendedType, fieldName)
        .build();
  }

  private MethodSpec collectionCollectionSetter(final Descriptor d, final ExecutableElement field) {
    String fieldName = fieldName(field);
    ClassName collectionType = ClassName.get(Collection.class);
    TypeName itemType = collectionGenericArgument(field);
    WildcardTypeName extendedType = WildcardTypeName.subtypeOf(itemType);

    MethodSpec.Builder setter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(ParameterizedTypeName.get(collectionType, extendedType), fieldName)
        .returns(builderType(d));

    collectionNullGuard(setter, field);
    if (shouldEnforceNonNull(field)) {
      setter.beginControlFlow("for ($T item : $N)", itemType, fieldName);
      assertNotNull(setter, "item", fieldName + ": null item");
      setter.endControlFlow();
    }

    setter.addStatement("this.$N = new $T<$T>($N)", fieldName, collectionImplType(field), itemType, fieldName)
        .addStatement("return this");
    return setter.build();
  }

  private MethodSpec collectionIterableSetter(final Descriptor d, final ExecutableElement field) {
    String fieldName = fieldName(field);
    ClassName iterableType = ClassName.get(Iterable.class);
    TypeName itemType = collectionGenericArgument(field);
    WildcardTypeName extendedType = WildcardTypeName.subtypeOf(itemType);

    MethodSpec.Builder setter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(ParameterizedTypeName.get(iterableType, extendedType), fieldName)
        .returns(builderType(d));

    collectionNullGuard(setter, field);

    ClassName collectionType = ClassName.get(Collection.class);
    setter.beginControlFlow("if ($N instanceof $T)", fieldName, collectionType)
        .addStatement("return $N(($T<$T>) $N)", fieldName, collectionType, extendedType, fieldName)
        .endControlFlow();

    setter.addStatement("return $N($N.iterator())", fieldName, fieldName);
    return setter.build();
  }

  private MethodSpec collectionIteratorSetter(final Descriptor d, final ExecutableElement field) {
    String fieldName = fieldName(field);
    ClassName iteratorType = ClassName.get(Iterator.class);
    TypeName itemType = collectionGenericArgument(field);
    WildcardTypeName extendedType = WildcardTypeName.subtypeOf(itemType);

    MethodSpec.Builder setter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(ParameterizedTypeName.get(iteratorType, extendedType), fieldName)
        .returns(builderType(d));

    collectionNullGuard(setter, field);

    setter.addStatement("this.$N = new $T<$T>()", fieldName, collectionImplType(field), itemType)
        .beginControlFlow("while ($N.hasNext())", fieldName)
        .addStatement("$T item = $N.next()", itemType, fieldName);

    if (shouldEnforceNonNull(field)) {
      assertNotNull(setter, "item", fieldName + ": null item");
    }

    setter.addStatement("this.$N.add(item)", fieldName)
        .endControlFlow()
        .addStatement("return this");
    return setter.build();
  }

  private MethodSpec collectionVarargSetter(final Descriptor d, final ExecutableElement field) {
    String fieldName = fieldName(field);
    TypeName itemType = collectionGenericArgument(field);

    MethodSpec.Builder setter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(ArrayTypeName.of(itemType), fieldName)
        .varargs()
        .returns(builderType(d));

    collectionNullGuard(setter, field);

    return setter.addStatement("return $N($T.asList($N))", fieldName, ClassName.get(Arrays.class), fieldName)
        .build();
  }

  private MethodSpec collectionAdder(final Descriptor d, final ExecutableElement field) {
    final String fieldName = fieldName(field);
    final String singular = singular(fieldName);
    if (singular == null || singular.isEmpty()) {
      return null;
    }

    final String appendMethodName = "add" + capitalizeFirstLetter(singular);
    final TypeName itemType = collectionGenericArgument(field);
    MethodSpec.Builder adder = MethodSpec.methodBuilder(appendMethodName)
        .addModifiers(PUBLIC)
        .addParameter(itemType, singular)
        .returns(builderType(d));

    if (shouldEnforceNonNull(field)) {
      assertNotNull(adder, singular);
    }
    lazyCollectionInitialization(adder, field);


    return adder.addStatement("$L.add($L)", fieldName, singular)
        .addStatement("return this")
        .build();
  }

  private void collectionNullGuard(final MethodSpec.Builder spec, final ExecutableElement field) {
    String fieldName = fieldName(field);
    if (shouldEnforceNonNull(field)) {
      assertNotNull(spec, fieldName);
    } else {
      spec.beginControlFlow("if ($N == null)", fieldName)
          .addStatement("this.$N = null", fieldName)
          .addStatement("return this")
          .endControlFlow();
    }
  }

  private void lazyCollectionInitialization(final MethodSpec.Builder spec, final ExecutableElement field) {
    final String fieldName = fieldName(field);
    final ClassName collectionImplType = collectionImplType(field);
    final TypeName itemType = collectionGenericArgument(field);
    spec.beginControlFlow("if (this.$N == null)", fieldName)
        .addStatement("this.$N = new $T<$T>()", fieldName, collectionImplType, itemType)
        .endControlFlow();
  }

  private MethodSpec mapSetter(final Descriptor d, final ExecutableElement field) {
    final String fieldName = fieldName(field);
    final TypeName keyType = WildcardTypeName.subtypeOf(genericArgument(field, 0));
    final TypeName valueType = WildcardTypeName.subtypeOf(genericArgument(field, 1));
    final TypeName paramType = ParameterizedTypeName.get(ClassName.get(Map.class), keyType, valueType);

    MethodSpec.Builder setter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(paramType, fieldName)
        .returns(builderType(d));

    if (shouldEnforceNonNull(field)) {
      final String entryName = variableName("entry", fieldName);
      assertNotNull(setter, fieldName);
      setter.beginControlFlow(
          "for ($T<$T, $T> $L : $N.entrySet())",
          ClassName.get(Map.Entry.class), keyType, valueType, entryName, fieldName);
      assertNotNull(setter, entryName + ".getKey()", fieldName + ": null key");
      assertNotNull(setter, entryName + ".getValue()", fieldName + ": null value");
      setter.endControlFlow();
    } else {
      setter.beginControlFlow("if ($N == null)", fieldName)
          .addStatement("this.$N = null", fieldName)
          .addStatement("return this")
          .endControlFlow();
    }

    setter.addStatement(
        "this.$N = new $T<$T, $T>($N)",
        fieldName, ClassName.get(HashMap.class), genericArgument(field, 0), genericArgument(field, 1), fieldName);

    return setter.addStatement("return this").build();
  }

  private MethodSpec mapSetterPairs(final Descriptor d, final ExecutableElement field, int entries) {
    checkArgument(entries > 0, "entries");
    final String fieldName = fieldName(field);
    final TypeName keyType = genericArgument(field, 0);
    final TypeName valueType = genericArgument(field, 1);

    MethodSpec.Builder setter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .returns(builderType(d));

    for (int i = 1; i < entries + 1; i++) {
      setter.addParameter(keyType, "k" + i);
      setter.addParameter(valueType, "v" + i);
    }

    // Recursion
    if (entries > 1) {
      final List<String> recursionParameters = Lists.newArrayList();
      for (int i = 1; i < entries; i++) {
        recursionParameters.add("k" + i);
        recursionParameters.add("v" + i);
      }
      setter.addStatement("$L($L)", fieldName, Joiner.on(", ").join(recursionParameters));
    }

    // Null checks
    final String keyName = "k" + entries;
    final String valueName = "v" + entries;
    if (shouldEnforceNonNull(field)) {
      assertNotNull(setter, keyName, fieldName + ": " + keyName);
      assertNotNull(setter, valueName, fieldName + ": " + valueName);
    }

    // Map instantiation
    if (entries == 1) {
      setter.addStatement("$N = new $T<$T, $T>()", fieldName, ClassName.get(HashMap.class), keyType, valueType);
    }

    // Put
    setter.addStatement("$N.put($N, $N)", fieldName, keyName, valueName);

    return setter.addStatement("return this").build();
  }

  private MethodSpec mapPutter(final Descriptor d, final ExecutableElement field) {
    final String fieldName = fieldName(field);
    final String singular = singular(fieldName);
    if (singular == null) {
      return null;
    }

    final String putSingular = "put" + capitalizeFirstLetter(singular);
    final TypeName keyType = genericArgument(field, 0);
    final TypeName valueType = genericArgument(field, 1);

    MethodSpec.Builder setter = MethodSpec.methodBuilder(putSingular)
        .addModifiers(PUBLIC)
        .addParameter(keyType, "key")
        .addParameter(valueType, "value")
        .returns(builderType(d));

    // Null checks
    if (shouldEnforceNonNull(field)) {
      assertNotNull(setter, "key", singular + ": key");
      assertNotNull(setter, "value", singular + ": value");
    }

    // Put
    lazMapInitialization(setter, field);
    setter.addStatement("$N.put(key, value)", fieldName);

    return setter.addStatement("return this").build();
  }

  private void lazMapInitialization(final MethodSpec.Builder spec, final ExecutableElement field) {
    final String fieldName = fieldName(field);
    final TypeName keyType = genericArgument(field, 0);
    final TypeName valueType = genericArgument(field, 1);
    spec.beginControlFlow("if (this.$N == null)", fieldName)
        .addStatement("this.$N = new $T<$T, $T>()", fieldName, ClassName.get(HashMap.class), keyType, valueType)
        .endControlFlow();
  }

  private MethodSpec setter(final Descriptor d, final ExecutableElement field) throws AutoMatterProcessorException {
    String fieldName = fieldName(field);

    MethodSpec.Builder setter = MethodSpec.methodBuilder(fieldName)
        .addModifiers(PUBLIC)
        .addParameter(fieldType(field), fieldName)
        .returns(builderType(d));

    if (shouldEnforceNonNull(field)) {
      assertNotNull(setter, fieldName);
    }

    return setter.addStatement("this.$N = $N", fieldName, fieldName)
        .addStatement("return this")
        .build();
  }

  private MethodSpec toBuilder(final Descriptor d) {
    return MethodSpec.methodBuilder("builder")
        .addModifiers(PUBLIC)
        .returns(builderType(d))
        .addStatement("return new $T(this)", builderType(d))
        .build();
  }

  private MethodSpec build(final Descriptor d) throws AutoMatterProcessorException {
    MethodSpec.Builder build = MethodSpec.methodBuilder("build")
        .addModifiers(PUBLIC)
        .returns(valueType(d));

    final List<String> parameters = Lists.newArrayList();
    for (ExecutableElement field : d.fields) {
      final String fieldName = fieldName(field);
      final TypeName fieldType = fieldType(field);

      if (isCollection(field) && shouldEnforceNonNull(field)) {
        TypeName genericArg = collectionGenericArgument(field);
        ClassName collections = ClassName.get(Collections.class);

        build.addStatement(
            "$T _$L = ($L != null) ? $T.$L(new $T<$T>($N)) : $T.<$T>$L()",
            fieldType, fieldName, fieldName,
            collections, unmodifiableCollection(field), collectionImplType(field), genericArg, fieldName,
            collections, genericArg, emptyCollection(field));
        parameters.add("_" + fieldName);
      } else if (isCollection(field)) {
        TypeName genericArg = collectionGenericArgument(field);
        ClassName collections = ClassName.get(Collections.class);

        build.addStatement(
            "$T _$L = ($L != null) ? $T.$L(new $T<$T>($N)) : null",
            fieldType, fieldName, fieldName,
            collections, unmodifiableCollection(field), collectionImplType(field), genericArg, fieldName);
        parameters.add("_" + fieldName);
      } else if (isMap(field) && shouldEnforceNonNull(field)) {
        ClassName collections = ClassName.get(Collections.class);
        TypeName keyType = genericArgument(field, 0);
        TypeName valueType = genericArgument(field, 1);

        build.addStatement(
            "$T _$L = ($L != null) ? $T.unmodifiableMap(new $T<$T, $T>($N)) : $T.<$T, $T>emptyMap()",
            fieldType, fieldName, fieldName,
            collections, ClassName.get(HashMap.class), keyType, valueType, fieldName,
            collections, keyType, valueType);
        parameters.add("_" + fieldName);
      } else if (isMap(field)) {
        ClassName collections = ClassName.get(Collections.class);
        TypeName keyType = genericArgument(field, 0);
        TypeName valueType = genericArgument(field, 1);

        build.addStatement(
            "$T _$L = ($L != null) ? $T.unmodifiableMap(new $T<$T, $T>($N)) : null",
            fieldType, fieldName, fieldName,
            collections, ClassName.get(HashMap.class), keyType, valueType, fieldName);
        parameters.add("_" + fieldName);
      } else {
        parameters.add(fieldName(field));
      }
    }

    return build.addStatement("return new Value($N)", Joiner.on(", ").join(parameters))
        .build();
  }

  private MethodSpec fromValue(final Descriptor d) {
    return MethodSpec.methodBuilder("from")
        .addModifiers(PUBLIC, STATIC)
        .addParameter(valueType(d), "v")
        .returns(builderType(d))
        .addStatement("return new $T(v)", builderType(d))
        .build();
  }

  private MethodSpec fromBuilder(final Descriptor d) {
    return MethodSpec.methodBuilder("from")
        .addModifiers(PUBLIC, STATIC)
        .addParameter(builderType(d), "v")
        .returns(builderType(d))
        .addStatement("return new $T(v)", builderType(d))
        .build();
  }

  private TypeSpec valueClass(final Descriptor d) throws AutoMatterProcessorException {
    TypeSpec.Builder value = TypeSpec.classBuilder("Value")
        .addModifiers(PRIVATE, STATIC, FINAL)
        .addSuperinterface(valueType(d));

    for (ExecutableElement field : d.fields) {
      value.addField(FieldSpec.builder(fieldType(field), fieldName(field), PRIVATE, FINAL).build());
    }

    value.addMethod(valueConstructor(d));

    for (ExecutableElement field : d.fields) {
      value.addMethod(valueGetter(field));
    }
    value.addMethod(valueToBuilder(d));
    value.addMethod(valueEquals(d));
    value.addMethod(valueHashCode(d));
    value.addMethod(valueToString(d));

    return value.build();
  }

  private MethodSpec valueConstructor(final Descriptor d) throws AutoMatterProcessorException {
    MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
        .addModifiers(PRIVATE);

    for (ExecutableElement field : d.fields) {
      if (shouldEnforceNonNull(field) && !isCollection(field) && !isMap(field)) {
        assertNotNull(constructor, fieldName(field));
      }
    }

    for (ExecutableElement field : d.fields) {
      String fieldName = fieldName(field);
      AnnotationSpec annotation = AnnotationSpec.builder(AutoMatter.Field.class)
          .addMember("value", "$S", fieldName)
          .build();
      ParameterSpec parameter = ParameterSpec.builder(fieldType(field), fieldName)
          .addAnnotation(annotation)
          .build();
      constructor.addParameter(parameter);

      final ClassName collectionsType = ClassName.get(Collections.class);
      if (shouldEnforceNonNull(field) && isCollection(field)) {
        final TypeName itemType = genericArgument(field, 0);
        constructor.addStatement(
            "this.$N = ($N != null) ? $N : $T.<$T>$L()",
            fieldName, fieldName, fieldName, collectionsType, itemType, emptyCollection(field));
      } else if (shouldEnforceNonNull(field) && isMap(field)) {
        final TypeName keyType = genericArgument(field, 0);
        final TypeName valueType = genericArgument(field, 1);
        constructor.addStatement(
            "this.$N = ($N != null) ? $N : $T.<$T, $T>emptyMap()",
            fieldName, fieldName, fieldName, collectionsType, keyType, valueType);
      } else {
        constructor.addStatement("this.$N = $N", fieldName, fieldName);
      }
    }

    return constructor.build();
  }

  private MethodSpec valueGetter(final ExecutableElement field) throws AutoMatterProcessorException {
    String fieldName = fieldName(field);

    return MethodSpec.methodBuilder(fieldName)
        .addAnnotation(AutoMatter.Field.class)
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .returns(fieldType(field))
        .addStatement("return $N", fieldName)
        .build();
  }

  private MethodSpec valueToBuilder(final Descriptor d) {
    MethodSpec.Builder toBuilder = MethodSpec.methodBuilder("builder")
        .addModifiers(PUBLIC)
        .returns(builderType(d))
        .addStatement("return new $T(this)", builderType(d));

    // Always emit toBuilder, but only annotate it with @Override if the target asked for it.
    if (d.toBuilder) {
      toBuilder.addAnnotation(Override.class);
    }

    return toBuilder.build();
  }

  private MethodSpec valueEquals(final Descriptor d) throws AutoMatterProcessorException {
    MethodSpec.Builder equals = MethodSpec.methodBuilder("equals")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .addParameter(ClassName.get(Object.class), "o")
        .returns(TypeName.BOOLEAN);

    equals.beginControlFlow("if (this == o)")
        .addStatement("return true")
        .endControlFlow();

    equals.beginControlFlow("if (!(o instanceof $T))", valueType(d))
        .addStatement("return false")
        .endControlFlow();

    if (! d.fields.isEmpty()) {
      equals.addStatement("final $T that = ($T) o", valueType(d), valueType(d));

      for (ExecutableElement field : d.fields) {
        equals.beginControlFlow(fieldNotEqualCondition(field))
            .addStatement("return false")
            .endControlFlow();
      }
    }

    equals.addStatement("return true");
    return equals.build();
  }

  private MethodSpec valueHashCode(final Descriptor d) throws AutoMatterProcessorException {
    MethodSpec.Builder hashcode = MethodSpec.methodBuilder("hashCode")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .returns(TypeName.INT)
        .addStatement("int result = 1")
        .addStatement("long temp");

    for (ExecutableElement field : d.fields) {
      final String name = fieldName(field);
      final TypeMirror type = field.getReturnType();
      switch (type.getKind()) {
        case LONG:
          hashcode.addStatement("result = 31 * result + (int) ($N ^ ($N >>> 32))", name, name);
          break;
        case INT:
          hashcode.addStatement("result = 31 * result + $N", name);
          break;
        case BOOLEAN:
          hashcode.addStatement("result = 31 * result + ($N ? 1231 : 1237)", name);
          break;
        case BYTE:
        case SHORT:
        case CHAR:
          hashcode.addStatement("result = 31 * result + (int) $N", name);
          break;
        case FLOAT:
          hashcode.addStatement(
              "result = 31 * result + ($N != +0.0f ? $T.floatToIntBits($N) : 0)",
              name, ClassName.get(Float.class), name);
          break;
        case DOUBLE:
          hashcode.addStatement("temp = $T.doubleToLongBits($N)", ClassName.get(Double.class), name);
          hashcode.addStatement("result = 31 * result + (int) (temp ^ (temp >>> 32))");
          break;
        case ARRAY:
          hashcode.addStatement(
              "result = 31 * result + ($N != null ? $T.hashCode($N) : 0)",
              name, ClassName.get(Arrays.class), name);
          break;
        case DECLARED:
          hashcode.addStatement("result = 31 * result + ($N != null ? $N.hashCode() : 0)", name, name);
          break;
        case ERROR:
          throw fail("Cannot resolve type, might be missing import: " + type, field);
        default:
          throw fail("Unsupported type: " + type, field);
      }
    }
    hashcode.addStatement("return result");
    return hashcode.build();
  }

  private MethodSpec valueToString(final Descriptor d) {
    return MethodSpec.methodBuilder("toString")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .returns(ClassName.get(String.class))
        .addStatement("return $N", toStringStatement(d.fields, d.targetSimpleName))
        .build();
  }

  private String toStringStatement(final List<ExecutableElement> fields,
                                     final String targetName){
    final StringBuilder builder = new StringBuilder();
    builder.append("\"").append(targetName).append("{\" +\n");
    boolean first = true;
    for (ExecutableElement field : fields) {
      final String comma = first ? "" : ", ";
      final String name = fieldName(field);
      if (field.getReturnType().getKind() == ARRAY) {
        builder.append(format("\"%1$s%2$s=\" + Arrays.toString(%2$s) +\n", comma, name));
      } else {
        builder.append(format("\"%1$s%2$s=\" + %2$s +\n", comma, name));
      }
      first = false;
    }
    builder.append("'}'");
    return builder.toString();
  }

  private void assertNotNull(MethodSpec.Builder spec, String name) {
    assertNotNull(spec, name, name);
  }

  private void assertNotNull(MethodSpec.Builder spec, String name, String msg) {
    spec.beginControlFlow("if ($N == null)", name)
        .addStatement("throw new $T($S)", ClassName.get(NullPointerException.class), msg)
        .endControlFlow();
  }

  private ClassName builderType(final Descriptor d) {
    return ClassName.get(d.packageName, d.builderSimpleName);
  }

  private ClassName valueType(final Descriptor d) {
    return ClassName.get(d.packageName, d.targetSimpleName);
  }

  private TypeName fieldType(final ExecutableElement field) throws AutoMatterProcessorException {
    TypeMirror returnType = field.getReturnType();
    if (returnType.getKind() == TypeKind.ERROR) {
      throw fail("Cannot resolve type, might be missing import: " + returnType, field);
    }
    return TypeName.get(returnType);
  }

  private TypeName collectionGenericArgument(final ExecutableElement field) {
    return genericArgument(field, 0);
  }

  private TypeName genericArgument(final ExecutableElement field, int index) {
    final DeclaredType type = (DeclaredType) field.getReturnType();
    checkArgument(type.getTypeArguments().size() >= index);
    return TypeName.get(type.getTypeArguments().get(index));
  }

  private ClassName collectionImplType(final ExecutableElement field) {
    return ClassName.get("java.util", collection(field));
  }

  private ClassName collectionRawType(final ExecutableElement field) {
    final DeclaredType type = (DeclaredType) field.getReturnType();
    return ClassName.get("java.util", type.asElement().getSimpleName().toString());
  }

  private static String optionalEmptyName(final ExecutableElement field) {
    final String returnType = field.getReturnType().toString();
    if (returnType.startsWith("com.google.common.base.Optional<")) {
      return "absent";
    }
    return "empty";
  }

  private static String optionalMaybeName(final ExecutableElement field) {
    final String returnType = field.getReturnType().toString();
    if (returnType.startsWith("com.google.common.base.Optional<")) {
      return "fromNullable";
    }
    return "ofNullable";
  }

  private boolean isCollection(final ExecutableElement field) {
    final String returnType = field.getReturnType().toString();
    return returnType.startsWith("java.util.List<") ||
           returnType.startsWith("java.util.Set<");
  }

  private String collection(final ExecutableElement field) {
    final String type = collectionType(field);
    if (type.equals("List")) {
      return "ArrayList";
    } else if (type.equals("Set")) {
      return "HashSet";
    } else {
      throw new AssertionError();
    }
  }

  private String unmodifiableCollection(final ExecutableElement field) {
    final String type = collectionType(field);
    if (type.equals("List")) {
      return "unmodifiableList";
    } else if (type.equals("Set")) {
      return "unmodifiableSet";
    } else {
      throw new AssertionError();
    }
  }

  private String emptyCollection(final ExecutableElement field) {
    final String type = collectionType(field);
    if (type.equals("List")) {
      return "emptyList";
    } else if (type.equals("Set")) {
      return "emptySet";
    } else {
      throw new AssertionError();
    }
  }

  private String collectionType(final ExecutableElement field) {
    final String returnType = field.getReturnType().toString();
    if (returnType.startsWith("java.util.List<")) {
      return "List";
    } else if (returnType.startsWith("java.util.Set<")) {
      return "Set";
    } else {
      throw new AssertionError();
    }
  }

  private String optionalType(final ExecutableElement field) {
    final String returnType = field.getReturnType().toString();
    if (returnType.startsWith("java.util.Optional<")) {
      return "java.util.Optional";
    } else if (returnType.startsWith("com.google.common.base.Optional<")) {
      return "com.google.common.base.Optional";
    }
    return returnType;
  }

  private boolean isMap(final ExecutableElement field) {
    final String returnType = field.getReturnType().toString();
    return returnType.startsWith("java.util.Map<");
  }

  private boolean isPrimitive(final ExecutableElement field) {
    return field.getReturnType().getKind().isPrimitive();
  }

  private String fieldNotEqualCondition(final ExecutableElement field)
      throws AutoMatterProcessorException {
    final String name = field.getSimpleName().toString();
    final TypeMirror type = field.getReturnType();
    switch (type.getKind()) {
      case LONG:
      case INT:
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case CHAR:
        return format("if (%1$s != that.%1$s())", name);
      case FLOAT:
        return format("if (Float.compare(that.%1$s(), %1$s) != 0)", name);
      case DOUBLE:
        return format("if (Double.compare(that.%1$s(), %1$s) != 0)", name);
      case ARRAY:
        return format("if (!Arrays.equals(%1$s, that.%1$s()))", name);
      case DECLARED:
        return format("if (%1$s != null ? !%1$s.equals(that.%1$s()) : that.%1$s() != null)", name);
      case ERROR:
        throw fail("Cannot resolve type, might be missing import: " + type, field);
      default:
        throw fail("Unsupported type: " + type, field);
    }
  }

  private boolean isOptional(final ExecutableElement field) {
    final String returnType = field.getReturnType().toString();
    return returnType.startsWith("java.util.Optional<") ||
           returnType.startsWith("com.google.common.base.Optional<");
  }

  private String singular(final String name) {
    final String singular = INFLECTOR.singularize(name);
    if (KEYWORDS.contains(singular)) {
      return null;
    }
    if (elements.getTypeElement("java.lang." + singular) != null) {
      return null;
    }
    return name.equals(singular) ? null : singular;
  }

  private String variableName(final String name, final String... scope) {
    return variableName(name, ImmutableSet.copyOf(scope));
  }

  private String variableName(final String name, final Set<String> scope) {
    if (!scope.contains(name)) {
      return name;
    }
    return variableName("_" + name, scope);
  }

  private String fieldName(final ExecutableElement field) {
    return field.getSimpleName().toString();
  }

  private static String simpleName(String fullyQualifiedName) {
    int lastDot = fullyQualifiedName.lastIndexOf('.');
    return fullyQualifiedName.substring(lastDot + 1, fullyQualifiedName.length());
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return ImmutableSet.of(AutoMatter.class.getName());
  }

  private class Descriptor {

    private final List<ExecutableElement> fields;
    private final boolean toBuilder;
    private final String builderFullName;
    private final String packageName;
    private final String targetSimpleName;
    private final String builderSimpleName;
    private final boolean isPublic;

    private Descriptor(final Element element) throws AutoMatterProcessorException {
      this.isPublic = element.getModifiers().contains(PUBLIC);
      this.packageName = elements.getPackageOf(element).getQualifiedName().toString();
      this.targetSimpleName = nestedName(element);
      final String targetName = element.getSimpleName().toString();
      this.builderFullName = fullyQualifedName(packageName, targetName + "Builder");
      this.builderSimpleName = simpleName(builderFullName);

      if (!element.getKind().isInterface()) {
        error("@AutoMatter target must be an interface", element);
      }

      final ImmutableList.Builder<ExecutableElement> fields = ImmutableList.builder();
      boolean toBuilder = false;
      for (final Element member : element.getEnclosedElements()) {
        if (member.getKind().equals(ElementKind.METHOD)) {
          final ExecutableElement executable = (ExecutableElement) member;
          if (executable.getModifiers().contains(STATIC)) {
            continue;
          }
          if (executable.getSimpleName().toString().equals("builder")) {
            final String type = executable.getReturnType().toString();
            if (!type.equals(builderSimpleName) && !type.equals(builderFullName)) {
              throw fail("builder() return type must be " + builderSimpleName, element);
            }
            toBuilder = true;
            continue;
          }
          fields.add(executable);
        }
      }
      this.fields = fields.build();
      this.toBuilder = toBuilder;
    }

    private String nestedName(final Element element) {
      final List<Element> classes = enclosingClasses(element);
      final List<String> names = Lists.newArrayList();
      for (Element cls : classes) {
        names.add(cls.getSimpleName().toString());
      }
      return Joiner.on('.').join(names);
    }

    private List<Element> enclosingClasses(final Element element) {
      final List<Element> classes = Lists.newArrayList();
      Element e = element;
      while (e.getKind() != PACKAGE) {
        classes.add(e);
        e = e.getEnclosingElement();
      }
      reverse(classes);
      return classes;
    }

    private String fullyQualifedName(final String packageName, final String simpleName) {
      return packageName.isEmpty()
          ? simpleName
          : packageName + "." + simpleName;
    }
  }

  private boolean shouldEnforceNonNull(final ExecutableElement field) {
    return !isPrimitive(field) && !isNullableAnnotated(field);
  }

  private boolean isNullableAnnotated(final ExecutableElement field) {
    return nullableAnnotation(field) != null;
  }

  private AnnotationMirror nullableAnnotation(final ExecutableElement field) {
    for (AnnotationMirror annotation : field.getAnnotationMirrors()) {
      if (annotation.getAnnotationType().asElement().getSimpleName().contentEquals("Nullable")) {
        return annotation;
      }
    }
    return null;
  }

  private AutoMatterProcessorException fail(final String msg, final Element element)
      throws AutoMatterProcessorException {
    throw new AutoMatterProcessorException(msg, element);
  }

  private void error(final String s, final Element element) {
    messager.printMessage(ERROR, s, element);
  }

  private static String capitalizeFirstLetter(String s) {
    if (s == null) {
      throw new NullPointerException("s");
    }
    if (s.isEmpty()) {
      return "";
    }
    return s.substring(0, 1).toUpperCase() + (s.length() > 1 ? s.substring(1) : "");
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}
