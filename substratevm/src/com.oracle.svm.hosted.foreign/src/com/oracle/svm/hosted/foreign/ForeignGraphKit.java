/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.foreign;

import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.java.NewArrayNode;
import org.graalvm.compiler.replacements.nodes.ReadRegisterNode;
import org.graalvm.compiler.replacements.nodes.WriteRegisterNode;

import com.oracle.graal.pointsto.infrastructure.GraphProvider;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.hosted.phases.HostedGraphKit;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

class ForeignGraphKit extends HostedGraphKit {
    ForeignGraphKit(DebugContext debug, HostedProviders providers, ResolvedJavaMethod method, GraphProvider.Purpose purpose) {
        super(debug, providers, method);
    }

    Pair<List<ValueNode>, ValueNode> unpackArgumentsAndExtractNEP(ValueNode argumentsArray, MethodType methodType) {
        List<ValueNode> args = loadArrayElements(argumentsArray, JavaKind.Object, methodType.parameterCount() + 1);
        ValueNode nep = args.remove(args.size() - 1);
        return Pair.create(args, nep);
    }

    public ValueNode packArguments(List<ValueNode> arguments) {
        MetaAccessProvider metaAccess = getMetaAccess();
        ValueNode argumentArray = append(new NewArrayNode(metaAccess.lookupJavaType(Object.class), ConstantNode.forInt(arguments.size(), getGraph()), false));
        for (int i = 0; i < arguments.size(); ++i) {
            var argument = arguments.get(i);
            assert argument.getStackKind().equals(JavaKind.Object);
            createStoreIndexed(argumentArray, i, JavaKind.Object, argument);
        }
        return argumentArray;
    }

    List<ValueNode> unboxArguments(List<ValueNode> args, MethodType methodType) {
        assert args.size() == methodType.parameterCount() : args.size() + " " + methodType.parameterCount();
        for (int i = 0; i < args.size(); ++i) {
            ValueNode argument = args.get(i);
            argument = createUnboxing(argument, JavaKind.fromJavaClass(methodType.parameterType(i)));
            args.set(i, argument);
        }
        return args;
    }

    public List<ValueNode> boxArguments(List<ValueNode> args, MethodType methodType) {
        assert args.size() == methodType.parameterCount() : args.size() + " " + methodType.parameterCount();
        for (int i = 0; i < args.size(); ++i) {
            ValueNode argument = args.get(i);
            JavaKind kind = JavaKind.fromJavaClass(methodType.parameterType(i));
            ResolvedJavaType boxed = getMetaAccess().lookupJavaType(kind.toBoxedJavaClass());
            argument = createBoxing(argument, kind, boxed);
            args.set(i, argument);
        }
        return args;
    }

    public ValueNode boxAndReturn(ValueNode returnValue, MethodType methodType) {
        JavaKind returnKind = JavaKind.fromJavaClass(methodType.returnType());
        if (returnKind.equals(JavaKind.Void)) {
            return createReturn(createObject(null), JavaKind.Object);
        }

        var boxed = getMetaAccess().lookupJavaType(returnKind.toBoxedJavaClass());
        return createReturn(createBoxing(returnValue, returnKind, boxed), JavaKind.Object);
    }

    public ValueNode unbox(ValueNode returnValue, MethodType methodType) {
        JavaKind returnKind = JavaKind.fromJavaClass(methodType.returnType());
        if (returnKind.equals(JavaKind.Void)) {
            return returnValue;
        }
        return createUnboxing(returnValue, returnKind);
    }

    public ValueNode createReturn(ValueNode returnValue, MethodType methodType) {
        JavaKind returnKind = JavaKind.fromJavaClass(methodType.returnType());
        return createReturn(returnValue, returnKind);
    }

    public ValueNode bindRegister(Register register, JavaKind kind) {
        /*
         * It seems like, intuitively, incoming should be set to true, but this doesn't work as the
         * block already has incoming edges (the function arguments?). Seems to be working anyway.
         */
        return append(new ReadRegisterNode(register, kind, false, false));
    }

    public Map<Register, ValueNode> saveRegisters(Iterable<Register> registers) {
        return StreamSupport.stream(registers.spliterator(), false)
                        .map(register -> Pair.create(register, bindRegister(register, wordTypes.getWordKind())))
                        .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
    }

    public void restoreRegisters(Map<Register, ValueNode> save) {
        for (var pair : save.entrySet()) {
            Register register = pair.getKey();
            ValueNode value = pair.getValue();
            append(new WriteRegisterNode(register, value));
        }
    }
}
