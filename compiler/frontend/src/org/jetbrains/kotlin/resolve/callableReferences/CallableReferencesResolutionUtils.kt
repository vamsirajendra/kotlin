/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.callableReferences

import org.jetbrains.kotlin.builtins.ReflectionTypes
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.diagnostics.Errors.CALLABLE_REFERENCE_LHS_NOT_A_CLASS
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.resolve.calls.CallResolver
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.ResolveArgumentsMode
import org.jetbrains.kotlin.resolve.calls.context.BasicCallResolutionContext
import org.jetbrains.kotlin.resolve.calls.context.CheckArgumentTypesMode
import org.jetbrains.kotlin.resolve.calls.context.ResolutionContext
import org.jetbrains.kotlin.resolve.calls.context.TemporaryTraceAndCache
import org.jetbrains.kotlin.resolve.calls.results.OverloadResolutionResults
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.scopes.BaseLexicalScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScopeKind
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.ScopeUtils
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.receivers.TransientReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.memberScopeAsImportingScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.DoubleColonLHS
import org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils
import org.jetbrains.kotlin.utils.Printer

private fun <D : CallableDescriptor> ResolveArgumentsMode.acceptResolution(results: OverloadResolutionResults<D>, trace: TemporaryTraceAndCache) {
    when (this) {
        ResolveArgumentsMode.SHAPE_FUNCTION_ARGUMENTS ->
            if (results.isSingleResult) trace.commit()
        ResolveArgumentsMode.RESOLVE_FUNCTION_ARGUMENTS ->
            if (results.isSomething()) trace.commit()
    }
}

private fun resolvePossiblyAmbiguousCallableReference(
        reference: KtSimpleNameExpression,
        receiver: ReceiverValue?,
        context: ResolutionContext<*>,
        resolutionMode: ResolveArgumentsMode,
        callResolver: CallResolver
): OverloadResolutionResults<CallableDescriptor> {
    val call = CallMaker.makeCall(reference, receiver, null, reference, emptyList())
    val temporaryTrace = TemporaryTraceAndCache.create(context, "resolve callable reference as function", reference)
    val newContext = if (resolutionMode == ResolveArgumentsMode.SHAPE_FUNCTION_ARGUMENTS)
        context.replaceTraceAndCache(temporaryTrace).replaceExpectedType(TypeUtils.NO_EXPECTED_TYPE)
    else
        context.replaceTraceAndCache(temporaryTrace)
    val callResolutionContext = BasicCallResolutionContext.create(
            newContext, call, CheckArgumentTypesMode.CHECK_CALLABLE_TYPE)
    val resolutionResults = callResolver.resolveCallForMember(reference, callResolutionContext)
    resolutionMode.acceptResolution(resolutionResults, temporaryTrace)
    return resolutionResults
}

private fun OverloadResolutionResults<*>.isSomething(): Boolean = !isNothing

fun resolvePossiblyAmbiguousCallableReference(
        callableReferenceExpression: KtCallableReferenceExpression,
        lhs: DoubleColonLHS?,
        context: ResolutionContext<*>,
        resolutionMode: ResolveArgumentsMode,
        callResolver: CallResolver
): OverloadResolutionResults<CallableDescriptor>? {
    val reference = callableReferenceExpression.callableReference

    fun resolveInScope(traceTitle: String, classifier: ClassifierDescriptor, staticScope: MemberScope): OverloadResolutionResults<CallableDescriptor> {

        // todo: drop this class when new resolve will be finished
        class StaticScopeAsLexicalScope(val staticScope: MemberScope) : BaseLexicalScope(staticScope.memberScopeAsImportingScope(), classifier) {
            override val kind: LexicalScopeKind
                get() = LexicalScopeKind.CALLABLE_REFERENCE

            override fun printStructure(p: Printer) {
                p.println(toString())
            }

            override fun toString(): String = "${javaClass.canonicalName} for $staticScope"

            // this method is needed for correct rewrite LEXICAL_SCOPE in trace
            override fun equals(other: Any?) = other is StaticScopeAsLexicalScope && other.staticScope == staticScope
            override fun hashCode() = staticScope.hashCode()
        }

        val temporaryTraceAndCache = TemporaryTraceAndCache.create(context, traceTitle, reference)
        val newContext = context.replaceTraceAndCache(temporaryTraceAndCache).replaceScope(StaticScopeAsLexicalScope(staticScope))
        val results = resolvePossiblyAmbiguousCallableReference(reference, null, newContext, resolutionMode, callResolver)
        resolutionMode.acceptResolution(results, temporaryTraceAndCache)
        return results
    }

    fun resolveWithReceiver(traceTitle: String, receiver: ReceiverValue): OverloadResolutionResults<CallableDescriptor> {
        val temporaryTraceAndCache = TemporaryTraceAndCache.create(context, traceTitle, reference)
        val newContext = context.replaceTraceAndCache(temporaryTraceAndCache)
        val results = resolvePossiblyAmbiguousCallableReference(reference, receiver, newContext, resolutionMode, callResolver)
        resolutionMode.acceptResolution(results, temporaryTraceAndCache)
        return results
    }

    val lhsType = lhs?.type ?: return resolvePossiblyAmbiguousCallableReference(reference, null, context, resolutionMode, callResolver)

    when (lhs) {
        is DoubleColonLHS.Type -> {
            val classifier = lhsType.constructor.declarationDescriptor
            if (classifier !is ClassDescriptor) {
                context.trace.report(CALLABLE_REFERENCE_LHS_NOT_A_CLASS.on(callableReferenceExpression))
                return null
            }

            val possibleStatic = resolveInScope("resolve unbound callable reference in static scope", classifier, classifier.staticScope)
            if (possibleStatic.isSomething()) return possibleStatic

            val possibleNested = resolveInScope(
                    "resolve unbound callable reference in static nested classes scope", classifier,
                    ScopeUtils.getStaticNestedClassesScope(classifier)
            )
            if (possibleNested.isSomething()) return possibleNested

            val possibleWithReceiver = resolveWithReceiver("resolve unbound callable reference with receiver", TransientReceiver(lhsType))
            if (possibleWithReceiver.isSomething()) return possibleWithReceiver
        }
        is DoubleColonLHS.Expression -> {
            val result = resolveWithReceiver("resolve bound callable reference", ExpressionReceiver.create(
                    callableReferenceExpression.receiverExpression!!, lhsType, context.trace.bindingContext
            ))
            if (result.isSomething()) return result
        }
    }

    return null
}

fun createKCallableTypeForReference(
        descriptor: CallableDescriptor,
        lhs: DoubleColonLHS?,
        reflectionTypes: ReflectionTypes,
        scopeOwnerDescriptor: DeclarationDescriptor
): KotlinType? {
    val receiverType =
            if (descriptor.extensionReceiverParameter != null || descriptor.dispatchReceiverParameter != null)
                (lhs as? DoubleColonLHS.Type)?.type
            else null

    return when (descriptor) {
        is FunctionDescriptor -> {
            val returnType = descriptor.returnType ?: return null
            val valueParametersTypes = ExpressionTypingUtils.getValueParametersTypes(descriptor.valueParameters)
            return reflectionTypes.getKFunctionType(Annotations.EMPTY, receiverType, valueParametersTypes, returnType)
        }
        is PropertyDescriptor -> {
            val mutable = descriptor.isVar && run {
                val setter = descriptor.setter
                // TODO: support private-to-this
                setter == null || Visibilities.isVisible(null, setter, scopeOwnerDescriptor)
            }
            reflectionTypes.getKPropertyType(Annotations.EMPTY, receiverType, descriptor.type, mutable)
        }
        is VariableDescriptor -> null
        else -> throw UnsupportedOperationException("Callable reference resolved to an unsupported descriptor: $descriptor")
    }
}
