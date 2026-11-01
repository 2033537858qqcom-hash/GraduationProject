package cn.zzuit.campus.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Fixed-point quote for demo room-utility recharge; money is always in cents. */
public record DormRechargeQuote(long amountCents, BigDecimal quantity) {
    private static final long MAX_CENTS=100_000_000L;
    public static DormRechargeQuote byAmount(long unitPriceCents,long amountCents) {
        validatePrice(unitPriceCents);
        if(amountCents<1 || amountCents>MAX_CENTS) throw new IllegalArgumentException("充值金额无效");
        var units=BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(unitPriceCents),9,RoundingMode.DOWN);
        if(units.signum()<=0) throw new IllegalArgumentException("充值用量过小");
        return new DormRechargeQuote(amountCents,units);
    }
    public static DormRechargeQuote byQuantity(long unitPriceCents,BigDecimal quantity) {
        validatePrice(unitPriceCents);
        if(quantity==null || quantity.signum()<=0 || quantity.scale()>3 || quantity.compareTo(new BigDecimal("1000000"))>0)
            throw new IllegalArgumentException("充值用量无效");
        try {
            long cents=quantity.multiply(BigDecimal.valueOf(unitPriceCents)).setScale(0,RoundingMode.HALF_UP).longValueExact();
            if(cents<1 || cents>MAX_CENTS) throw new IllegalArgumentException("充值金额无效");
            return new DormRechargeQuote(cents,quantity);
        } catch(ArithmeticException ex) { throw new IllegalArgumentException("充值金额无效",ex); }
    }
    private static void validatePrice(long price) {
        if(price<1 || price>MAX_CENTS) throw new IllegalArgumentException("收费标准无效");
    }
}
