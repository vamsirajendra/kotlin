/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.load.java.lazy.descriptors

import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.incremental.record
import org.jetbrains.kotlin.load.java.lazy.LazyJavaResolverContext
import org.jetbrains.kotlin.load.java.structure.JavaPackage
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.storage.getValue
import org.jetbrains.kotlin.util.collectionUtils.getFirstMatch
import org.jetbrains.kotlin.util.collectionUtils.getFromAllScopes
import org.jetbrains.kotlin.utils.Printer
import org.jetbrains.kotlin.utils.toReadOnlyList

class JvmPackageScope(
        private val c: LazyJavaResolverContext,
        jPackage: JavaPackage,
        private val packageFragment: LazyJavaPackageFragment
): MemberScope {
    internal val javaScope = LazyJavaPackageScope(c, jPackage, packageFragment)

    private val kotlinScopes by c.storageManager.createLazyValue {
        packageFragment.binaryClasses.values.mapNotNull { partClass ->
            c.components.deserializedDescriptorResolver.createKotlinPackagePartScope(packageFragment, partClass)
        }.toReadOnlyList()
    }

    override fun getContributedClassifier(name: Name, location: LookupLocation): ClassifierDescriptor? {
        recordLookup(location, name)

        val javaClassifier = javaScope.getContributedClassifier(name, location)
        if (javaClassifier != null) return javaClassifier

        return getFirstMatch(kotlinScopes) { it.getContributedClassifier(name, location) }
    }

    override fun getContributedVariables(name: Name, location: LookupLocation): Collection<PropertyDescriptor> {
        recordLookup(location, name)
        return getFromAllScopes(javaScope, kotlinScopes) { it.getContributedVariables(name, location) }
    }

    override fun getContributedFunctions(name: Name, location: LookupLocation): Collection<SimpleFunctionDescriptor> {
        recordLookup(location, name)
        return getFromAllScopes(javaScope, kotlinScopes) { it.getContributedFunctions(name, location) }
    }

    override fun getContributedDescriptors(
            kindFilter: DescriptorKindFilter, nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> =
            getFromAllScopes(javaScope, kotlinScopes) { it.getContributedDescriptors(kindFilter, nameFilter) }

    override fun printScopeStructure(p: Printer) {
        p.println(javaClass.simpleName, " {")
        p.pushIndent()

        p.println("containingDeclaration: $packageFragment")
        javaScope.printScopeStructure(p)

        for (kotlinScope in kotlinScopes) {
            kotlinScope.printScopeStructure(p)
        }

        p.popIndent()
        p.println("}")
    }

    private fun recordLookup(location: LookupLocation, name: Name) {
        c.components.lookupTracker.record(location, packageFragment, name)
    }
}
