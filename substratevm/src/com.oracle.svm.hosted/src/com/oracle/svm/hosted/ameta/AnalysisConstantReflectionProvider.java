/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.ameta;

import java.util.function.ObjIntConsumer;

import org.graalvm.compiler.core.common.type.TypedConstant;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.WordBase;

import com.oracle.graal.pointsto.heap.ImageHeapArray;
import com.oracle.graal.pointsto.heap.ImageHeapConstant;
import com.oracle.graal.pointsto.heap.ImageHeapInstance;
import com.oracle.graal.pointsto.heap.value.ValueSupplier;
import com.oracle.graal.pointsto.infrastructure.UniverseMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.core.RuntimeAssertionsSupport;
import com.oracle.svm.core.annotate.InjectAccessors;
import com.oracle.svm.core.graal.meta.SharedConstantReflectionProvider;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.SVMHost;
import com.oracle.svm.hosted.classinitialization.ClassInitializationSupport;
import com.oracle.svm.hosted.meta.HostedMetaAccess;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MemoryAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

@Platforms(Platform.HOSTED_ONLY.class)
public class AnalysisConstantReflectionProvider extends SharedConstantReflectionProvider {
    private final AnalysisUniverse universe;
    private final UniverseMetaAccess metaAccess;
    private final ClassInitializationSupport classInitializationSupport;

    public AnalysisConstantReflectionProvider(AnalysisUniverse universe, UniverseMetaAccess metaAccess, ClassInitializationSupport classInitializationSupport) {
        this.universe = universe;
        this.metaAccess = metaAccess;
        this.classInitializationSupport = classInitializationSupport;
    }

    @Override
    public MemoryAccessProvider getMemoryAccessProvider() {
        return EmptyMemoryAcessProvider.SINGLETON;
    }

    @Override
    public JavaConstant boxPrimitive(JavaConstant source) {
        if (!source.getJavaKind().isPrimitive()) {
            return null;
        }
        return SubstrateObjectConstant.forObject(source.asBoxedPrimitive());
    }

    @Override
    public JavaConstant unboxPrimitive(JavaConstant source) {
        if (!source.getJavaKind().isObject()) {
            return null;
        }
        if (source instanceof ImageHeapConstant heapConstant) {
            return JavaConstant.forBoxedPrimitive(SubstrateObjectConstant.asObject(heapConstant.getHostedObject()));
        }
        return JavaConstant.forBoxedPrimitive(SubstrateObjectConstant.asObject(source));
    }

    @Override
    public Integer readArrayLength(JavaConstant array) {
        if (array.getJavaKind() != JavaKind.Object || array.isNull()) {
            return null;
        }
        if (array instanceof ImageHeapConstant) {
            if (array instanceof ImageHeapArray) {
                return ((ImageHeapArray) array).getLength();
            }
            return null;
        }
        return super.readArrayLength(array);
    }

    @Override
    public JavaConstant readArrayElement(JavaConstant array, int index) {
        if (array.getJavaKind() != JavaKind.Object || array.isNull()) {
            return null;
        }
        if (array instanceof ImageHeapConstant) {
            if (array instanceof ImageHeapArray heapArray) {
                if (index < 0 || index >= heapArray.getLength()) {
                    return null;
                }
                return replaceObject(heapArray.readElementValue(index));
            }
            return null;
        }
        JavaConstant element = super.readArrayElement(array, index);
        return element == null ? null : replaceObject(element);
    }

    @Override
    public void forEachArrayElement(JavaConstant array, ObjIntConsumer<JavaConstant> consumer) {
        if (array instanceof ImageHeapConstant) {
            if (array instanceof ImageHeapArray heapArray) {
                for (int index = 0; index < heapArray.getLength(); index++) {
                    JavaConstant element = heapArray.readElementValue(index);
                    consumer.accept(replaceObject(element), index);
                }
            }
            return;
        }
        /* Intercept the original consumer and apply object replacement. */
        super.forEachArrayElement(array, (element, index) -> consumer.accept(replaceObject(element), index));
    }

    @Override
    public final JavaConstant readFieldValue(ResolvedJavaField field, JavaConstant receiver) {
        return readValue(metaAccess, (AnalysisField) field, receiver);
    }

    public JavaConstant readValue(UniverseMetaAccess suppliedMetaAccess, AnalysisField field, JavaConstant receiver) {
        if (!field.isStatic()) {
            if (receiver.isNull() || !field.getDeclaringClass().isAssignableFrom(((TypedConstant) receiver).getType(metaAccess))) {
                /*
                 * During compiler optimizations, it is possible to see field loads with a constant
                 * receiver of a wrong type. The code will later be removed as dead code, and in
                 * most cases the field read would also be rejected as illegal by the HotSpot
                 * constant reflection provider doing the actual field load. But there are several
                 * other ways how a field can be accessed, e.g., our ReadableJavaField mechanism or
                 * fields of classes that are initialized at image run time. To avoid any surprises,
                 * we abort the field reading here early.
                 */
                return null;
            }
        }

        JavaConstant value;
        if (receiver instanceof ImageHeapConstant) {
            ImageHeapInstance heapObject = (ImageHeapInstance) receiver;
            return interceptValue(suppliedMetaAccess, field, heapObject.readFieldValue(field));
        }
        value = universe.lookup(ReadableJavaField.readFieldValue(suppliedMetaAccess, classInitializationSupport, field.wrapped, universe.toHosted(receiver)));

        return interceptValue(suppliedMetaAccess, field, value);
    }

    /** Read the field value and wrap it in a value supplier without performing any replacements. */
    public ValueSupplier<JavaConstant> readHostedFieldValue(AnalysisField field, HostedMetaAccess hMetaAccess, JavaConstant receiver) {
        if (field.wrapped instanceof ReadableJavaField) {
            ReadableJavaField readableField = (ReadableJavaField) field.wrapped;
            if (readableField.isValueAvailableBeforeAnalysis()) {
                /* Materialize and return the value. */
                return ValueSupplier.eagerValue(universe.lookup(readableField.readValue(metaAccess, classInitializationSupport, receiver)));
            } else {
                /*
                 * Return a lazy value. This applies to RecomputeFieldValue.Kind.FieldOffset and
                 * RecomputeFieldValue.Kind.Custom. The value becomes available during hosted
                 * universe building and is installed by calling
                 * ComputedValueField.processSubstrate() or by ComputedValueField.readValue().
                 * Attempts to materialize the value earlier will result in an error.
                 */
                return ValueSupplier.lazyValue(() -> universe.lookup(readableField.readValue(hMetaAccess, classInitializationSupport, receiver)),
                                readableField::isValueAvailable);
            }
        }
        return ValueSupplier.eagerValue(universe.lookup(ReadableJavaField.readFieldValue(metaAccess, classInitializationSupport, field.wrapped, receiver)));
    }

    public JavaConstant interceptValue(UniverseMetaAccess suppliedMetaAccess, AnalysisField field, JavaConstant value) {
        JavaConstant result = value;
        if (result != null) {
            result = filterInjectedAccessor(field, result);
            result = replaceObject(result);
            result = interceptAssertionStatus(field, result);
            result = interceptWordType(suppliedMetaAccess, field, result);
        }
        return result;
    }

    private static JavaConstant filterInjectedAccessor(AnalysisField field, JavaConstant value) {
        if (field.getAnnotation(InjectAccessors.class) != null) {
            /*
             * Fields whose accesses are intercepted by injected accessors are not actually present
             * in the image. Ideally they should never be read, but there are corner cases where
             * this happens. We intercept the value and return 0 / null.
             */
            assert !field.isAccessed();
            return JavaConstant.defaultForKind(value.getJavaKind());
        }
        return value;
    }

    /**
     * Run all registered object replacers.
     */
    private JavaConstant replaceObject(JavaConstant value) {
        if (value == JavaConstant.NULL_POINTER) {
            return JavaConstant.NULL_POINTER;
        }
        if (value instanceof ImageHeapConstant) {
            /* The value is replaced when the object is snapshotted. */
            return value;
        }
        if (value.getJavaKind() == JavaKind.Object) {
            Object oldObject = universe.getSnippetReflection().asObject(Object.class, value);
            Object newObject = universe.replaceObject(oldObject);
            if (newObject != oldObject) {
                return universe.getSnippetReflection().forObject(newObject);
            }
        }
        return value;
    }

    /**
     * Intercept assertion status: the value of the field during image generation does not matter at
     * all (because it is the hosted assertion status), we instead return the appropriate runtime
     * assertion status. Field loads are also intrinsified early in
     * {@link com.oracle.svm.hosted.phases.EarlyConstantFoldLoadFieldPlugin}, but we could still see
     * such a field here if user code, e.g., accesses it via reflection.
     */
    private static JavaConstant interceptAssertionStatus(AnalysisField field, JavaConstant value) {
        if (field.isStatic() && field.isSynthetic() && field.getName().startsWith("$assertionsDisabled")) {
            Class<?> clazz = field.getDeclaringClass().getJavaClass();
            boolean assertionsEnabled = RuntimeAssertionsSupport.singleton().desiredAssertionStatus(clazz);
            return JavaConstant.forBoolean(!assertionsEnabled);
        }
        return value;
    }

    /**
     * Intercept {@link Word} types. They are boxed objects in the hosted world, but primitive
     * values in the runtime world.
     */
    private JavaConstant interceptWordType(UniverseMetaAccess suppliedMetaAccess, AnalysisField field, JavaConstant value) {
        if (value.getJavaKind() == JavaKind.Object) {
            if (universe.hostVM().isRelocatedPointer(suppliedMetaAccess, value)) {
                /*
                 * Such pointers are subject to relocation therefore we don't know their values yet.
                 * Therefore there should not be a relocated pointer constant in a function which is
                 * compiled. RelocatedPointers are only allowed in non-constant fields. The caller
                 * of readValue is responsible of handling the returned value correctly.
                 */
                return value;
            } else if (suppliedMetaAccess.isInstanceOf(value, WordBase.class)) {
                Object originalObject = universe.getSnippetReflection().asObject(Object.class, value);
                return JavaConstant.forIntegerKind(universe.getWordKind(), ((WordBase) originalObject).rawValue());
            } else if (value.isNull() && field.getType().isWordType()) {
                return JavaConstant.forIntegerKind(universe.getWordKind(), 0);
            }
        }
        return value;
    }

    @Override
    public AnalysisType asJavaType(Constant constant) {
        if (constant instanceof SubstrateObjectConstant substrateConstant) {
            Object obj = universe.getSnippetReflection().asObject(Object.class, substrateConstant);
            if (obj instanceof DynamicHub hub) {
                return getHostVM().lookupType(hub);
            } else if (obj instanceof Class) {
                throw VMError.shouldNotReachHere("Must not have java.lang.Class object: " + obj);
            }
        }
        if (constant instanceof ImageHeapConstant) {
            if (metaAccess.isInstanceOf((JavaConstant) constant, Class.class)) {
                throw VMError.shouldNotReachHere("ConstantReflectionProvider.asJavaType(Constant) not yet implemented for ImageHeapObject");
            }
        }
        return null;
    }

    @Override
    public JavaConstant asJavaClass(ResolvedJavaType type) {
        return SubstrateObjectConstant.forObject(getHostVM().dynamicHub(type));
    }

    private SVMHost getHostVM() {
        return (SVMHost) universe.hostVM();
    }
}
