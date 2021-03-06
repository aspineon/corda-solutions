package com.r3.businessnetworks.billing.flows.member

import co.paralleluniverse.fibers.Suspendable
import com.r3.businessnetworks.billing.flows.member.service.MemberBillingDatabaseService
import com.r3.businessnetworks.billing.states.BillingContract
import com.r3.businessnetworks.billing.states.BillingState
import com.r3.businessnetworks.billing.states.BillingStateStatus
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * Returns [billingState] to the state issuer. [BillingStateStatus.RETURNED] [BillingState]s and associated [BillingChipState]s can not be used anymore.
 * It's important to attach all unspent [BillingChipState]s before returning the [BillingState].
 *
 * @param billingState the [BillingState] to be returned
 * @return returned [BillingState] with the associated [SignedTransaction]
 */
@StartableByRPC
@InitiatingFlow
class ReturnBillingStateFlow(private val billingState : StateAndRef<BillingState>) : FlowLogic<Pair<BillingState, SignedTransaction>>() {
    @Suspendable
    override fun call() : Pair<BillingState, SignedTransaction> {
        val notary = serviceHub.networkMapCache.notaryIdentities.single()
        val outputBillingState = billingState.state.data.copy(status = BillingStateStatus.RETURNED)
        val builder = TransactionBuilder(notary)
                .addInputState(billingState)
                .addOutputState(outputBillingState, BillingContract.CONTRACT_NAME)
                .addCommand(BillingContract.Commands.Return(), billingState.state.data.participants.map { it.owningKey })

        builder.verify(serviceHub)

        val stx = serviceHub.signInitialTransaction(builder)

        val session = initiateFlow(billingState.state.data.issuer)

        val allSignedTx = subFlow(CollectSignaturesFlow(stx, listOf(session)))

        return Pair(outputBillingState, subFlow(FinalityFlow(allSignedTx, listOf(session))))
    }
}

/**
 * Attaches all of unspent [BillingChipState]s and returns the [billingState] to the issuer.
 *
 * @param billingState the billing state to attach the [BillingChipState]s to
 * @return the [BillingState] with the associated [SignedTransaction]
 */
@StartableByRPC
class AttachUnspentChipsAndReturnBillingStateFlow(private val billingState : StateAndRef<BillingState>) : FlowLogic<Pair<BillingState, SignedTransaction>>() {
    @Suspendable
    override fun call() : Pair<BillingState, SignedTransaction> {
        val result = subFlow(AttachAllUnspentChipsFlow(billingState))
        val databaseService = serviceHub.cordaService(MemberBillingDatabaseService::class.java)
        val billingState = if (result == null) billingState else databaseService.getBillingStateByLinearId(billingState.state.data.linearId)!!
        return subFlow(ReturnBillingStateFlow(billingState))
    }
}