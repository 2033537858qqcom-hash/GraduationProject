package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DormRechargeQuoteTest {
    @Test void quoteByQuantityRoundsMoneyOnceAndRetainsQuantity() {
        var quote=DormRechargeQuote.byQuantity(61,new BigDecimal("10.000"));
        assertThat(quote.amountCents()).isEqualTo(610);
        assertThat(quote.quantity()).isEqualByComparingTo("10.000");
    }
    @Test void quoteByAmountUsesExactMoneyAndNeverOverstatesUnits() {
        var quote=DormRechargeQuote.byAmount(61,1000);
        assertThat(quote.amountCents()).isEqualTo(1000);
        assertThat(quote.quantity()).isEqualByComparingTo("16.393442622");
        assertThat(quote.quantity().multiply(BigDecimal.valueOf(61))).isLessThanOrEqualTo(BigDecimal.valueOf(1000));
    }
    @Test void invalidOrFreeAmountsAreRejected() {
        assertThatThrownBy(()->DormRechargeQuote.byAmount(0,100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DormRechargeQuote.byAmount(61,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DormRechargeQuote.byQuantity(1,new BigDecimal("0.001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DormRechargeQuote.byQuantity(61,new BigDecimal("0.0001"))).isInstanceOf(IllegalArgumentException.class);
    }
}
