package com.github.hept59434091.jprotobuf

import com.baidu.bjf.remoting.protobuf.Codec
import com.baidu.bjf.remoting.protobuf.EnumReadable
import com.baidu.bjf.remoting.protobuf.FieldType
import com.baidu.bjf.remoting.protobuf.ProtobufProxy
import com.baidu.bjf.remoting.protobuf.annotation.Ignore
import com.baidu.bjf.remoting.protobuf.annotation.Protobuf
import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass
import com.baidu.bjf.remoting.protobuf.code.CodedConstant
import com.baidu.bjf.remoting.protobuf.utils.ClassHelper
import com.baidu.bjf.remoting.protobuf.utils.FieldUtils
import com.baidu.bjf.remoting.protobuf.utils.ProtobufProxyUtils
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Descriptors
import com.google.protobuf.UninitializedMessageException
import com.squareup.javapoet.*
import lombok.SneakyThrows
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.tools.Diagnostic
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField

/**
 * @author <a href="luxueneng@baidu.com">luxueneng</a>
 * @since 2019-06-10
 */
class ProtoBufAnnotationProcessor : AbstractProcessor() {
    @Protobuf
    private val defaultProtoBuf = this::class.declaredMemberProperties
        .findLast { it.name == "defaultProtoBuf" }!!
        .javaField!!
        .getAnnotation(Protobuf::class.java)
    private val codecClassName = ClassName.get(Codec::class.java)
    private val codedOutputStreamClassName = ClassName.get(CodedOutputStream::class.java)
    private val codedInputStreamClassName = ClassName.get(CodedInputStream::class.java)
    private val codedConstantClassName = ClassName.get(CodedConstant::class.java)
    private val fieldTypeClassName = ClassName.get(FieldType::class.java)
    private val descriptorClassName = ClassName.get(Descriptors.Descriptor::class.java)
    private val listClassName = ClassName.get(List::class.java)
    private val arrayListClassName = ClassName.get(ArrayList::class.java)
    private val mapClassName = ClassName.get(Map::class.java)
    private val hashMapClassName = ClassName.get(HashMap::class.java)
    private val enumReadableClassName = ClassName.get(EnumReadable::class.java)
    private val protoBufProxyClassName = ClassName.get(ProtobufProxy::class.java)
    private val fieldUtilsClassName = ClassName.get(FieldUtils::class.java)
    private val uninitializedMessageExceptionClassName = ClassName.get(UninitializedMessageException::class.java)

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(
            Protobuf::class.java.canonicalName,
            ProtobufClass::class.java.canonicalName
        )
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latestSupported()
    }

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if (annotations.isEmpty()) {
            return true
        }
        roundEnv
            .rootElements
            .filter { it.getAnnotation(Ignore::class.java) == null }
            .map { it as TypeElement }
            .forEach { root ->
                val oriClassName = ClassName.get(root)

                val protoTypeSpecBuilder = getProtoTypeSpecBuilder(oriClassName)
                val fieldInfoList = generateFieldInfo(root)


                val size = MethodSpec
                    .methodBuilder("size")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.INT)
                    .addParameter(oriClassName, "object")
                    .addStatement("int size = 0")
                    .let { sizeMethodSpecBuilder ->
                        fieldInfoList.forEach {
                            appendSizeElement(
                                fieldInfo = it,
                                oriTypeElement = root,
                                sizeMethodSpecBuilder = sizeMethodSpecBuilder
                            )
                        }

                        return@let sizeMethodSpecBuilder
                    }
                    .addStatement("return size")
                    .build()

                val doWriteTo = MethodSpec
                    .methodBuilder("doWriteTo")
                    .addAnnotation(SneakyThrows::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.VOID)
                    .addParameter(oriClassName, "object")
                    .addParameter(codedOutputStreamClassName, "output")
                    .let { doWriteToMethodSpecBuilder ->
                        fieldInfoList.forEach {
                            appendDoWriteToElement(
                                fieldInfo = it,
                                oriTypeElement = root,
                                doWriteToMethodSpecBuilder = doWriteToMethodSpecBuilder
                            )
                        }
                        return@let doWriteToMethodSpecBuilder
                    }
                    .build()


                val readFrom = MethodSpec
                    .methodBuilder("readFrom")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(SneakyThrows::class.java)
                    .returns(oriClassName)
                    .addParameter(codedInputStreamClassName, "input")
                    .addStatement("\$T ret = new \$T()", oriClassName, oriClassName)
                    .let { readFromMethodSpecBuilder ->
                        fieldInfoList
                            .filter { it.fieldType == FieldType.ENUM }
                            .forEach {
                                appendReadFromEnumElement(
                                    fieldInfo = it,
                                    oriTypeElement = root,
                                    readFromMethodSpecBuilder = readFromMethodSpecBuilder
                                )
                            }
                        return@let readFromMethodSpecBuilder
                    }
                    .addStatement("boolean done = false")
                    .addStatement("\$T codec = null", codecClassName)
                    .beginControlFlow("while (!done)")
                    .addStatement("int tag = input.readTag()")
                    .beginControlFlow("if (tag == 0)")
                    .addStatement("break")
                    .endControlFlow()
                    .let { readFromMethodSpecBuilder ->
                        fieldInfoList
                            .forEach {
                                appendReadFromElement(
                                    fieldInfo = it,
                                    oriTypeElement = root,
                                    readFromMethodSpecBuilder = readFromMethodSpecBuilder
                                )
                            }
                        return@let readFromMethodSpecBuilder
                    }
                    .addStatement("input.skipField(tag)")
                    .endControlFlow()
                    .addStatement("return ret")
                    .build()


                val typeSpec = protoTypeSpecBuilder
                    .addMethod(size)
                    .addMethod(doWriteTo)
                    .addMethod(readFrom)
                    .build()


                val javaFile = JavaFile
                    .builder(root.enclosingElement.toString(), typeSpec)
                    .indent("    ")
                    .build()


                javaFile.writeTo(processingEnv.filer)
                javaFile.writeTo(System.out)
            }
        return true
    }

    private fun getProtoTypeSpecBuilder(oriClassName: ClassName): TypeSpec.Builder {
        val codecType = ParameterizedTypeName.get(codecClassName, oriClassName)
        val protoType = ClassName.get(
            oriClassName.packageName(),
            "${oriClassName.simpleName()}\$\$JProtoBufClass"
        )

        // field
        val descriptorFieldSpec = FieldSpec
            .builder(descriptorClassName, "descriptor", Modifier.PRIVATE)
            .build()

        // method
        val encodeMethodSpec = MethodSpec
            .methodBuilder("encode")
            .addAnnotation(SneakyThrows::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(oriClassName, "object")
            .returns(ArrayTypeName.of(TypeName.BYTE))
            .addStatement("int size = size(object)")
            .addStatement("byte[] result = new byte[size]")
            .addStatement(
                "\$T output = \$T.newInstance(result)",
                codedOutputStreamClassName, codedOutputStreamClassName
            )
            .addStatement("doWriteTo(object, output)")
            .addStatement("return result")
            .build()


        val decodeMethodSpec = MethodSpec
            .methodBuilder("decode")
            .addAnnotation(SneakyThrows::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(ArrayTypeName.of(TypeName.BYTE), "bytes")
            .returns(oriClassName)
            .addStatement(
                "\$T input = \$T.newInstance(bytes, 0, bytes.length)",
                codedInputStreamClassName, codedInputStreamClassName
            )
            .addStatement("return readFrom(input)")
            .build()

        val writeToMethodSpec = MethodSpec
            .methodBuilder("writeTo")
            .addAnnotation(SneakyThrows::class.java)
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeName.VOID)
            .addParameter(oriClassName, "object")
            .addParameter(codedOutputStreamClassName, "output")
            .addStatement("byte[] bytes = encode(object)")
            .addStatement("output.writeRawBytes(bytes)")
            .addStatement("output.flush()")
            .build()


        val getDescriptorMethodSpec = MethodSpec
            .methodBuilder("getDescriptor")
            .addAnnotation(SneakyThrows::class.java)
            .addModifiers(Modifier.PUBLIC)
            .returns(descriptorClassName)
            .beginControlFlow("if (descriptor != null)")
            .addStatement("return this.descriptor")
            .endControlFlow()
            .addStatement(
                "return (this.descriptor = \$T.getDescriptor(\$T.class))",
                codedConstantClassName,
                oriClassName
            )
            .build()


        return TypeSpec
            .classBuilder(protoType)
            .addModifiers(Modifier.PUBLIC)
            .addField(descriptorFieldSpec)
            .addSuperinterface(codecType)
            .addMethod(encodeMethodSpec)
            .addMethod(decodeMethodSpec)
            .addMethod(writeToMethodSpec)
            .addMethod(getDescriptorMethodSpec)
    }


    private fun appendDoWriteToElement(
            fieldInfo: FieldInfo,
            oriTypeElement: TypeElement,
            doWriteToMethodSpecBuilder: MethodSpec.Builder
    ) {
        val element = fieldInfo.field
        val order = fieldInfo.order
        val fieldType = fieldInfo.fieldType
        val isList = fieldInfo.isList

        val className = ClassName.get(element.asType())
        val accessByField = getAccessByField("object", element, oriTypeElement)
        val fieldName = CodedConstant.getFieldName(order)
        val encodeFieldType = CodedConstant.getFiledType(fieldType, isList)
            .let { if (it == "List") "java.util.List" else it }
        val writeValueToField = CodedConstant.getWriteValueToField(fieldType, accessByField, isList)

        // encodeWriteFieldValue
        val codeBook = if (isList) {
            CodeBlock.of(
                "\$T.writeToList(output, $order, \$T.${fieldType.type.toUpperCase()}, $fieldName)",
                codedConstantClassName, fieldTypeClassName
            )
        } else {
            // not list so should add convert to primitive type
            val realFieldName = if (fieldType == FieldType.ENUM
                && className == enumReadableClassName
            ) {
                "((${element.asType()}) $fieldName).value()"
            } else {
                "$fieldName${fieldType.toPrimitiveType}"
            }

            when (fieldType) {
                FieldType.OBJECT -> CodeBlock
                    .of(
                        "\$T.writeObject" +
                                "(output, $order, \$T.${fieldType.type.toUpperCase()}, $realFieldName, false)",
                        codedConstantClassName, fieldTypeClassName
                    )
                FieldType.STRING, FieldType.BYTES -> CodeBlock
                    .of("output.writeBytes($order, $realFieldName)")
                else -> CodeBlock
                    .of("output.write${CodedConstant.capitalize(fieldType.type)}($order, $realFieldName)")
            }
        }
        doWriteToMethodSpecBuilder
            .addStatement("$encodeFieldType $fieldName = null")
            .beginControlFlow("if (!CodedConstant.isNull($accessByField))")
            .addStatement("$fieldName = $writeValueToField")
            .beginControlFlow("if ($fieldName != null)")
            .addStatement(codeBook)
            .endControlFlow()
            .endControlFlow()
    }

    private fun appendSizeElement(
            fieldInfo: FieldInfo,
            oriTypeElement: TypeElement,
            sizeMethodSpecBuilder: MethodSpec.Builder
    ) {
        val element = fieldInfo.field
        val order = fieldInfo.order
        val fieldType = fieldInfo.fieldType
        val isList = fieldInfo.isList

        val className = ClassName.get(element.asType())
        val accessByField = getAccessByField("object", element, oriTypeElement)
        val fieldName = CodedConstant.getFieldName(order)
        val encodeFieldType = CodedConstant
            .getFiledType(fieldType, isList)
            .let { if (it == "List") "java.util.List" else it }
        val writeValueToField = CodedConstant.getWriteValueToField(fieldType, accessByField, isList)

        val typeString = fieldType.type.toUpperCase()
        val calcSize = when {
            isList -> "CodedConstant.computeListSize($order, $fieldName, FieldType.$typeString, false, null)"
            fieldType == FieldType.OBJECT -> "CodedConstant.computeSize($order,$fieldName, FieldType.$typeString,false, null)"
            else -> {
                val capitalized =
                    if (fieldType == FieldType.STRING || fieldType == FieldType.BYTES) {
                        "bytes"
                    } else {
                        fieldType.type
                    }.let { CodedConstant.capitalize(it) }

                val realFieldName = if (fieldType == FieldType.ENUM
                    && className == enumReadableClassName
                ) {
                    "((${element.asType()}) $fieldName).value()"
                } else {
                    "$fieldName${fieldType.toPrimitiveType}"
                }

                "com.google.protobuf.CodedOutputStream.compute${capitalized}Size($order,$realFieldName)"
            }
        }

        sizeMethodSpecBuilder
            .addStatement("$encodeFieldType $fieldName = null")
            .beginControlFlow("if (!\$T.isNull($accessByField))", codedConstantClassName)
            .addStatement("$fieldName = $writeValueToField")
            .addStatement("size += $calcSize")
            .endControlFlow()
            .beginControlFlow("if($fieldName == null)")
            .addStatement(
                "throw new \$T(\$T.asList(\"${element.simpleName}\"))",
                uninitializedMessageExceptionClassName, codedConstantClassName
            )
            .endControlFlow()
    }

    private fun appendReadFromEnumElement(
            fieldInfo: FieldInfo,
            oriTypeElement: TypeElement,
            readFromMethodSpecBuilder: MethodSpec.Builder
    ) {
        val element = fieldInfo.field
        val isList = fieldInfo.isList

        val clsName = element.asType().toString()
        if (!isList) {
            val express = CodeBlock
                .of(
                    "\$T.getEnumValue($clsName.class, $clsName.values()[0].name())",
                    codedConstantClassName
                )

            // add set get method
            readFromMethodSpecBuilder.addStatement(
                getSetToField(
                    "ret",
                    element,
                    oriTypeElement,
                    express,
                    isList,
                    fieldInfo.isMap
                )
            )
        }
    }

    private fun appendReadFromElement(
            fieldInfo: FieldInfo,
            oriTypeElement: TypeElement,
            readFromMethodSpecBuilder: MethodSpec.Builder
    ) {
        val fieldType = fieldInfo.fieldType
        val isList = fieldInfo.isList
        val typeStr = CodedConstant.capitalize(fieldInfo.fieldType.type)

        val decodeOrder = CodedConstant
            .makeTag(
                fieldInfo.order,
                fieldType.internalFieldType.wireType
            )
            .toString()

        val clsName = if (isList && fieldInfo.typeArg != TypeName.VOID) {
            fieldInfo.typeArg.toString()
        } else {
            fieldInfo.field.asType().toString()
        }

        readFromMethodSpecBuilder
            .beginControlFlow("if (tag == $decodeOrder)")
            .let {
                // objectDecodeExpress
                if (fieldType != FieldType.OBJECT) {
                    return@let it
                }

                return@let it
                    .addStatement("codec = \$T.create($clsName.class, false, null)", protoBufProxyClassName)
                    .addStatement("int length = input.readRawVarint32()")
                    .addStatement("final int oldLimit = input.pushLimit(length)")
            }
            .let {
                val express = when (fieldType) {
                    FieldType.ENUM -> CodeBlock
                        .of(
                            "\$T.getEnumValue(" +
                                    "$clsName.class, " +
                                    "\$T.getEnumName($clsName.values(), input.read$typeStr()))",
                            codedConstantClassName, codedConstantClassName
                        )
                    FieldType.OBJECT ->
                        CodeBlock
                            .of("($clsName) codec.readFrom(input)")
                    FieldType.BYTES ->
                        CodeBlock
                            .of("input.read$typeStr().toByteArray()")
                    else -> CodeBlock.of("input.read$typeStr()")
                }

                val setVal = getSetToField(
                    target = "ret",
                    element = fieldInfo.field,
                    oriTypeElement = oriTypeElement,
                    express = express,
                    isList = isList,
                    isMap = fieldInfo.isMap
                )

                return@let it
                    .addCode(setVal)
            }
            .let {
                if (fieldType != FieldType.OBJECT) {
                    return@let it
                }

                return@let it
                    .addStatement("input.checkLastTagWas(0)")
                    .addStatement("input.popLimit(oldLimit)")
            }
            .let {
                if (!fieldInfo.protoBuf.required) {
                    return@let it
                }

                return@let it
                    .beginControlFlow(
                        "if (\$T.isNull(${
                        getAccessByField("ret", fieldInfo.field, oriTypeElement)
                        }))", codedConstantClassName
                    )
                    .addStatement(
                        "throw new \$T(\$T.asList(\"${fieldInfo.field.simpleName}\"))",
                        uninitializedMessageExceptionClassName, codedConstantClassName
                    )
                    .endControlFlow()
            }
            .addStatement("continue")
            .endControlFlow()
    }

    private fun generateFieldInfo(root: TypeElement): MutableList<FieldInfo> {
        val fieldMap = root
            .enclosedElements
            .filter { it.kind.isField }
            .filter { !it.modifiers.contains(Modifier.TRANSIENT) }
            .filter { it.getAnnotation(Protobuf::class.java) != null }
            .groupBy { it.getAnnotation(Protobuf::class.java).order > 0 }

        val resList = mutableListOf<FieldInfo>()
        var maxOrder = 1
        val orderSet = mutableSetOf<Int>()
        fieldMap[true]
            ?.map {
                val protoBuf = it.getAnnotation(Protobuf::class.java)
                val order = protoBuf.order
                if (orderSet.contains(order)) {
                    processingEnv.messager.printMessage(
                        Diagnostic.Kind.ERROR, "duplicate order $order", it
                    )
                    throw RuntimeException("duplicate order $order")
                }

                val className = ClassName.get(it.asType())
                val isList = className is ParameterizedTypeName && className.rawType == listClassName
                val isMap = className is ParameterizedTypeName && className.rawType == mapClassName

                orderSet.add(order)
                maxOrder = order + 1
                FieldInfo(
                        field = it,
                        typeArg = getTypeArg(className),
                        protoBuf = protoBuf,
                        order = order,
                        fieldType = getFieldType(it, protoBuf),
                        isList = isList,
                        isMap = isMap
                )
            }
            ?.let { resList += it }

        fieldMap[false]
            ?.sortedBy { it.kind.ordinal }
            ?.map {
                val protoBuf = it.getAnnotation(Protobuf::class.java)
                val className = ClassName.get(it.asType())
                val isList = className is ParameterizedTypeName && className.rawType == listClassName
                val isMap = className is ParameterizedTypeName && className.rawType == mapClassName

                FieldInfo(
                        field = it,
                        typeArg = getTypeArg(className),
                        protoBuf = protoBuf,
                        order = maxOrder++,
                        fieldType = getFieldType(it, protoBuf),
                        isList = isList,
                        isMap = isMap
                )
            }
            ?.let { resList += it }

        if (root.getAnnotation(ProtobufClass::class.java) == null) {
            return resList
        }

        root
            .enclosedElements
            .asSequence()
            .filter { it.kind.isField }
            .filter { it.getAnnotation(Protobuf::class.java) == null }
            .filter { !it.modifiers.contains(Modifier.TRANSIENT) }
            .sortedBy { it.kind.ordinal }
            .map {
                val className = ClassName.get(it.asType())
                val isList = className is ParameterizedTypeName && className.rawType == listClassName
                val isMap = className is ParameterizedTypeName && className.rawType == mapClassName

                FieldInfo(
                        field = it,
                        typeArg = getTypeArg(className),
                        protoBuf = defaultProtoBuf,
                        order = maxOrder++,
                        fieldType = getFieldType(it, defaultProtoBuf),
                        isList = isList,
                        isMap = isMap
                )
            }
            .toList()
            .let { resList += it }


        return resList
    }

    private fun getTypeArg(className: TypeName): TypeName {
        if (className is ParameterizedTypeName) {
            if (className.typeArguments.size != 1) {
                val msg = "error type args size. must be 1"
                processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg)
                throw RuntimeException(msg)
            }

            return className.typeArguments[0]
        }
        return TypeName.VOID
    }

    private fun getFieldType(
        field: Element,
        protoBufAnnotation: Protobuf
    ): FieldType {
        return if (protoBufAnnotation.fieldType == FieldType.DEFAULT) {
            val className = ClassName.get(field.asType())
            val fieldTypeClass = field.asType()
                .let {
                    when {
                        className is ParameterizedTypeName
                                && className.rawType == listClassName -> Class.forName((it as DeclaredType).typeArguments[0].toString())
                        className.toString() == "int" ->
                            Int::class.java
                        className.toString() == "int[]" -> {
                            val msg = "type int[] is not supported!"
                            processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, field)
                            throw RuntimeException(msg)
                        }
                        className.toString() == "java.lang.Integer[]" -> {
                            val msg = "type Integer[] is not supported!"
                            processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, field)
                            throw RuntimeException(msg)
                        }
                        className.toString() == "byte" ->
                            Byte::class.java
                        className.toString() == "byte[]" ->
                            Class.forName("[B")
                        className.toString() == "java.lang.Byte[]" ->
                            Class.forName("[Ljava.lang.Byte;")
                        else -> Class.forName(it.toString())
                    }
                }

            ProtobufProxyUtils.TYPE_MAPPING[fieldTypeClass]
                ?: if (Enum::class.java.isAssignableFrom(fieldTypeClass)) {
                    FieldType.ENUM
                } else {
                    FieldType.OBJECT
                }
        } else {
            protoBufAnnotation.fieldType
        }
    }

    @Suppress("SameParameterValue")
    private fun getAccessByField(target: String, field: Element, cls: TypeElement): String {
        val fieldName = field.simpleName.toString()
        if (field.modifiers.contains(Modifier.PUBLIC)) {
            return "$target${ClassHelper.PACKAGE_SEPARATOR}$fieldName"
        }

        val typeName = field.asType().toString()
        // check if has getter method
        val getter: String = if ("boolean".equals(typeName, ignoreCase = true)) {
            "is${CodedConstant.capitalize(fieldName)}"
        } else {
            "get${CodedConstant.capitalize(fieldName)}"
        }

        if (cls.enclosedElements.map { it.simpleName.toString() }.contains(getter)) {
            return "$target${ClassHelper.PACKAGE_SEPARATOR}$getter()"
        }

        processingEnv.messager.printMessage(Diagnostic.Kind.WARNING, "could not found getter=$getter. using reflection")

        var type = field.asType().toString()
        if ("[B" == type || "[Ljava.lang.Byte;" == type || "java.lang.Byte[]" == type) {
            type = "byte[]"
        } else if ("java.lang.Byte" == type) {
            type = "java.lang.Integer"
        }

        // use reflection to get value
        return "(${FieldUtils.toObjectType(type)}) " +
                "com.baidu.bjf.remoting.protobuf.utils.FieldUtils.getField" +
                "($target, \"${field.simpleName}\")"
    }

    @Suppress("SameParameterValue")
    private fun getSetToField(
        target: String,
        element: Element,
        oriTypeElement: TypeElement,
        express: CodeBlock?,
        isList: Boolean,
        isMap: Boolean
    ): CodeBlock {
        return CodeBlock
            .builder()
            .let {
                if (!isList && !isMap) {
                    return@let it
                }
                return@let it.beginControlFlow("if ((${getAccessByField(target, element, oriTypeElement)}) == null)")
            }
            .let { builder ->
                val setter = "set${CodedConstant.capitalize(element.simpleName.toString())}"
                when {
                    element.modifiers.contains(Modifier.PUBLIC) ->
                        when {
                            isList ->
                                builder
                                    .addStatement("$target.${element.simpleName} = new \$T()", arrayListClassName)
                                    .endControlFlow()
                                    .let expressLet@{
                                        if (express == null) {
                                            return@expressLet it
                                        }

                                        return@expressLet it
                                            .addStatement(
                                                CodeBlock
                                                    .builder()
                                                    .add("$target.${element.simpleName}.add(")
                                                    .add(express)
                                                    .add(")")
                                                    .build()
                                            )
                                    }
                            isMap ->
                                builder
                                    .addStatement("$target.${element.simpleName} = new \$T()", hashMapClassName)
                                    .endControlFlow()
                                    .let expressLet@{
                                        if (express == null) {
                                            return@expressLet it
                                        }
                                        return@expressLet it.addStatement(express)
                                    }
                            else ->
                                builder
                                    .addStatement(
                                        CodeBlock
                                            .builder()
                                            .add(
                                                "$target${ClassHelper.PACKAGE_SEPARATOR}${element
                                                    .simpleName} = "
                                            )
                                            .add(express)
                                            .build()
                                    )
                        }
                    oriTypeElement.enclosedElements.map { it.simpleName.toString() }.contains(setter) -> {
                        when {
                            isList ->
                                builder
                                    .addStatement("\$T __list = new \$T()", listClassName, arrayListClassName)
                                    .addStatement("$target.$setter(__list)")
                                    .endControlFlow()
                                    .let expressLet@{
                                        if (express == null) {
                                            return@expressLet it
                                        }
                                        return@expressLet it
                                            .addStatement(
                                                CodeBlock
                                                    .builder()
                                                    .add(
                                                        "(${
                                                        getAccessByField(target, element, oriTypeElement)
                                                        }).add("
                                                    )
                                                    .add(express)
                                                    .add(")")
                                                    .build()
                                            )
                                    }
                            isMap ->
                                builder
                                    .addStatement("\$T __map = new \$T()", mapClassName, hashMapClassName)
                                    .addStatement("$target.$setter(__map)")
                                    .endControlFlow()
                                    .let expressLet@{
                                        if (express == null) {
                                            return@expressLet it
                                        }
                                        return@expressLet it.addStatement(express)
                                    }
                            else ->
                                builder
                                    .addStatement(
                                        CodeBlock
                                            .builder()
                                            .add("$target.$setter(")
                                            .add(express)
                                            .add(")")
                                            .build()
                                    )
                        }
                    }
                    else -> {
                        processingEnv.messager.printMessage(
                            Diagnostic.Kind.WARNING,
                            "could not found setter=$setter. using reflection"
                        )
                        when {
                            isList ->
                                builder
                                    .addStatement("\$T __list = new \$T()", listClassName, arrayListClassName)
                                    .addStatement(
                                        "\$T.setField($target, \"${element.simpleName}\", __list)",
                                        fieldUtilsClassName
                                    )
                                    .endControlFlow()
                                    .let expressLet@{ codeBlockBuilder ->
                                        if (express == null) {
                                            return@expressLet codeBlockBuilder
                                        }

                                        val expressCode = element
                                            .let { TypeName.get(it.asType()) }
                                            .let {
                                                if (it is ParameterizedTypeName) {
                                                    it.typeArguments[0]
                                                } else {
                                                    it
                                                }
                                            }
                                            .let {
                                                if (it == TypeName.BYTE
                                                    || it == TypeName.BYTE.box()
                                                ) {
                                                    CodeBlock
                                                        .builder()
                                                        .add("(byte)")
                                                        .add(express)
                                                        .build()
                                                } else {
                                                    express
                                                }
                                            }

                                        return@expressLet codeBlockBuilder
                                            .addStatement(
                                                CodeBlock
                                                    .builder()
                                                    .add(
                                                        "(${
                                                        getAccessByField(target, element, oriTypeElement)
                                                        }).add("
                                                    )
                                                    .add(expressCode)
                                                    .add(")")
                                                    .build()
                                            )
                                    }
                            isMap ->
                                builder
                                    .addStatement("\$T __map = new \$T()", mapClassName, hashMapClassName)
                                    .addStatement(
                                        "\$T.setField($target, \"${element.simpleName}\", __map)",
                                        fieldUtilsClassName
                                    )
                                    .endControlFlow()
                                    .let expressLet@{
                                        if (express == null) {
                                            return@expressLet it
                                        }
                                        return@expressLet it.addStatement(express)
                                    }

                            else -> {
                                if (express == null) {
                                    return@let builder
                                }

                                return@let builder
                                    .addStatement(
                                        CodeBlock
                                            .builder()
                                            .add(
                                                "\$T.setField($target, \"${element.simpleName}\", ",
                                                fieldUtilsClassName
                                            )
                                            .add(express)
                                            .add(")")
                                            .build()
                                    )
                            }
                        }
                    }
                }
            }
            .build()
    }
}