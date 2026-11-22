package com.catchtable.global.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptTransportConfigurationTest {
    @Test void socketTasksAreBoundedBeforeTheyAcquireACompleteHttpRequest() {
        int one=ReceiptTransportConfiguration.httpLimit(768L<<20,4_000);
        int perApp=ReceiptTransportConfiguration.httpLimit(256L<<20,4_000);
        assertThat(one).isEqualTo(1536);
        assertThat(3*perApp).isBetween(one-2,one);
        assertThat(ReceiptTransportConfiguration.httpLimit(256L<<20,2)).isEqualTo(2);
        assertThatThrownBy(()->ReceiptTransportConfiguration.httpLimit(256L<<20,0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
