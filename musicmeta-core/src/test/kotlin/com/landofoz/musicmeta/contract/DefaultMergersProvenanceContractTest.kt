package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.engine.DEFAULT_MERGERS
import com.landofoz.musicmeta.engine.ResultMerger

/**
 * The built-in merger set under [MergerProvenanceContract]. There is deliberately one subclass: the
 * contract's subject is the registered set rather than a single merger, so a ninth merger added to
 * that set is covered without an implementation of its own. A consumer-registered merger is
 * invisible here, which [MergerProvenanceContract] states as one of its two structural limits.
 */
class DefaultMergersProvenanceContractTest : MergerProvenanceContract() {
    override fun subject(): List<ResultMerger> = DEFAULT_MERGERS
}
