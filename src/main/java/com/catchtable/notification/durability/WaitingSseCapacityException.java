package com.catchtable.notification.durability;

public class WaitingSseCapacityException extends RuntimeException {
    public WaitingSseCapacityException() { super("Waiting SSE journal capacity exceeded"); }
}
