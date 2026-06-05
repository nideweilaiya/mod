package com.aiworkbench.companion.core.action;

/**
 * Result of a single {@link IAction#execute(com.aiworkbench.companion.core.perception.PerceptionData)} tick.
 */
public enum ActionResult {

    /** The action completed and the sequence may advance. */
    SUCCESS,

    /** The action is still running and should be ticked again. */
    IN_PROGRESS,

    /** The action failed in a generic, non-recoverable way. */
    FAILURE,

    /** The action cannot continue because the inventory cannot accept the target item. */
    INVENTORY_FULL,

    /** The action cannot continue because the required tool is missing or invalid. */
    TOOL_MISSING
}
