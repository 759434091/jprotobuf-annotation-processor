package com.github.hept59434091.jprotobuf

import com.baidu.bjf.remoting.protobuf.FieldType
import com.baidu.bjf.remoting.protobuf.annotation.Protobuf
import com.squareup.javapoet.TypeName
import javax.lang.model.element.Element

/**
 * @author <a href="luxueneng@baidu.com">luxueneng</a>
 * @since 2019-06-13
 */
data class FieldInfo(
        val field: Element,
        val typeArg: TypeName,
        val protoBuf: Protobuf,
        val order: Int,
        val fieldType: FieldType,
        val isList: Boolean,
        val isMap: Boolean
)