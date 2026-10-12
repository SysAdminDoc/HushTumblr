/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.rootmute

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(FixtureTests::class)
class AccountIdentityFixtureTest {
    private val owners = setOf(ACCOUNT_USER_INFO, ACCOUNT_REQUEST, ACCOUNT_SERVICE, ACCOUNT_CREDENTIALS, ACCOUNT_LOGOUT)

    @Test
    fun `fresh UUID commits only inside a fenced callback with exceptional cleanup`() {
        for (build in Fixtures.declaredBuilds()) {
            val native = FixtureDex.classes(build, owners)
            val context = PatchContexts.of(ExtensionDex.classes() + native.values)
            val plan = context.resolveAccountIdentity()
            assertEquals("$ACCOUNT_USER_INFO->A:Ljava/lang/String;", plan.uuidField)
            val oldPersist = plan.persist.instructionList().map { it.opcode }
            accountIdentityPatch.execute(context)
            val persist = plan.persist.instructionList()
            assertEquals("$ROOT_MUTES->beginAccountChange(Ljava/lang/Object;)V", persist.first().methodRef().toString())
            assertEquals(1, (persist.first() as RegisterRangeInstruction).startRegister)
            assertEquals("$ROOT_MUTES->commitAccount()V", persist[persist.lastIndex - 1].methodRef().toString())
            assertEquals(oldPersist.dropLast(1), persist.drop(1).dropLast(2).map { it.opcode })
            val bridge = context.mutableClassDefBy(ACCOUNT_USER_INFO).methods.single { it.name == "hushAccountId" }
            assertEquals(plan.uuidField, bridge.instructionList().first().fieldRef().toString())
            val accept = plan.accept.instructionList()
            assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.IGET_OBJECT, Opcode.CHECK_CAST, Opcode.INVOKE_STATIC,
                Opcode.INVOKE_STATIC, Opcode.RETURN_VOID, Opcode.MOVE_EXCEPTION, Opcode.INVOKE_STATIC, Opcode.THROW), accept.map { it.opcode })
            assertEquals("$ROOT_MUTES->requestEntered(Ljava/lang/Object;)V", accept[0].methodRef().toString())
            assertEquals("$ROOT_MUTES->requestExited()V", accept[4].methodRef().toString())
            assertEquals("$ROOT_MUTES->requestExited()V", accept[7].methodRef().toString())
            val cleanup = plan.accept.implementation!!.tryBlocks.single()
            assertEquals(3, cleanup.startCodeAddress)
            assertEquals(7, cleanup.codeUnitCount)
            assertNull(cleanup.exceptionHandlers.single().exceptionType)
            assertEquals(14, cleanup.exceptionHandlers.single().handlerCodeAddress)
            val replacement = plan.replace.instructionList()
            assertEquals(Opcode.MONITOR_ENTER, replacement[0].opcode)
            assertEquals("$ROOT_MUTES->credentialsChanging()V", replacement[1].methodRef().toString())
            val complete = replacement.indexOfFirst { it.methodRef()?.name == "credentialsChanged" }
            assertEquals(Opcode.MONITOR_EXIT, replacement[complete + 1].opcode)
            assertEquals("$ROOT_MUTES->logout()V", plan.reset.instructionList()[1].methodRef().toString())
            assertEquals("$ROOT_MUTES->logout()V", plan.logout.instructionList()[0].methodRef().toString())
        }
    }

    @Test
    fun `a callback casting another response refuses before any account lifecycle is changed`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val callback = context.mutableClassDefBy(ACCOUNT_REQUEST).methods.single { it.name == "accept" }
            callback.replaceInstruction(1, "check-cast p1, Ljava/lang/String;")
            assertThrows(PatchException::class.java) { accountIdentityPatch.execute(context) }
            assertFalse(HUSH_ACCOUNT in context.mutableClassDefBy(ACCOUNT_USER_INFO).interfaces)
            assertEquals(73, context.mutableClassDefBy(ACCOUNT_USER_INFO).methods.single { it.name == "M" }.instructionList().size)
            assertEquals(3, context.mutableClassDefBy(ACCOUNT_REQUEST).methods.single { it.name == "<init>" }.instructionList().size)
        }
    }

    @Test
    fun `an email field substituted for user_uuid refuses before the bridge is installed`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val persist = context.mutableClassDefBy(ACCOUNT_USER_INFO).methods.single { it.name == "M" }
            persist.replaceInstruction(50, "iget-object v0, p0, $ACCOUNT_USER_INFO->E:Ljava/lang/String;")
            assertThrows(PatchException::class.java) { accountIdentityPatch.execute(context) }
            assertFalse(HUSH_ACCOUNT in context.mutableClassDefBy(ACCOUNT_USER_INFO).interfaces)
            assertEquals(73, persist.instructionList().size)
        }
    }
}
