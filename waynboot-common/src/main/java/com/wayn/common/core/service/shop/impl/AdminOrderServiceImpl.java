package com.wayn.common.core.service.shop.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.binarywang.wxpay.bean.request.WxPayRefundRequest;
import com.github.binarywang.wxpay.bean.result.WxPayRefundResult;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.wayn.common.core.domain.shop.Order;
import com.wayn.common.core.domain.shop.OrderGoods;
import com.wayn.common.core.domain.tool.MailConfig;
import com.wayn.common.core.domain.vo.SendMailVO;
import com.wayn.common.core.mapper.shop.AdminOrderMapper;
import com.wayn.common.core.service.shop.IAdminOrderService;
import com.wayn.common.core.service.shop.IGoodsProductService;
import com.wayn.common.core.service.shop.IMemberService;
import com.wayn.common.core.service.shop.IOrderGoodsService;
import com.wayn.common.core.service.tool.IMailConfigService;
import com.wayn.common.core.util.OrderUtil;
import com.wayn.common.util.R;
import com.wayn.common.util.mail.MailUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
public class AdminOrderServiceImpl extends ServiceImpl<AdminOrderMapper, Order> implements IAdminOrderService {

    @Autowired
    private AdminOrderMapper adminOrderMapper;

    @Autowired
    private WxPayService wxPayService;

    @Autowired
    private IOrderGoodsService iOrderGoodsService;

    @Autowired
    private IGoodsProductService iGoodsProductService;

    @Autowired
    private IMemberService iMemberService;

    @Autowired
    private IMailConfigService mailConfigService;

    @Override
    public IPage<Order> selectListPage(IPage<Order> page, Order order) {
        return adminOrderMapper.selectOrderListPage(page, order);
    }

    @Override
    public R refund(Long orderId) {
        Order order = getById(orderId);
        if (order == null) {
            return R.error();
        }

        //  如果订单不是退款状态，则不能退款
        if (!order.getOrderStatus().equals(OrderUtil.STATUS_REFUND)) {
            return R.error("订单不能确认收货");
        }

        // 微信退款
        WxPayRefundRequest wxPayRefundRequest = new WxPayRefundRequest();
        wxPayRefundRequest.setOutTradeNo(order.getOrderSn());
        wxPayRefundRequest.setOutRefundNo("refund_" + order.getOrderSn());
        // 元转成分
        Integer totalFee = order.getActualPrice().multiply(new BigDecimal(100)).intValue();
        wxPayRefundRequest.setTotalFee(totalFee);
        wxPayRefundRequest.setRefundFee(totalFee);

        WxPayRefundResult wxPayRefundResult;
        try {
            wxPayRefundResult = wxPayService.refund(wxPayRefundRequest);
        } catch (WxPayException e) {
            log.error(e.getMessage(), e);
            return R.error("订单退款失败");
        }
        if (!wxPayRefundResult.getReturnCode().equals("SUCCESS")) {
            log.warn("refund fail: " + wxPayRefundResult.getReturnMsg());
            return R.error("订单退款失败");
        }
        if (!wxPayRefundResult.getResultCode().equals("SUCCESS")) {
            log.warn("refund fail: " + wxPayRefundResult.getReturnMsg());
            return R.error("订单退款失败");
        }

        LocalDateTime now = LocalDateTime.now();
        // 设置订单取消状态
        order.setOrderStatus(OrderUtil.STATUS_REFUND_CONFIRM);
        order.setOrderEndTime(now);
        // 记录订单退款相关信息
        order.setRefundAmount(order.getActualPrice());
        order.setRefundType("微信退款接口");
        order.setRefundContent(wxPayRefundResult.getRefundId());
        order.setRefundTime(now);
        order.setUpdateTime(new Date());
        updateById(order);
        // 商品货品数量增加
        List<OrderGoods> orderGoodsList = iOrderGoodsService.list(new QueryWrapper<OrderGoods>().eq("order_id", orderId));
        for (OrderGoods orderGoods : orderGoodsList) {
            Long productId = orderGoods.getProductId();
            Integer number = orderGoods.getNumber();
            if (iGoodsProductService.addStock(productId, number)) {
                throw new RuntimeException("商品货品库存增加失败");
            }
        }

        // 返还优惠券
//        List<LitemallCouponUser> couponUsers = couponUserService.findByOid(orderId);
//        for (LitemallCouponUser couponUser: couponUsers) {
//            // 优惠券状态设置为可使用
//            couponUser.setStatus(CouponUserConstant.STATUS_USABLE);
//            couponUser.setUpdateTime(LocalDateTime.now());
//            couponUserService.update(couponUser);
//        }

        //TODO 发送邮件和短信通知，这里采用异步发送
        // 退款成功通知用户, 例如“您申请的订单退款 [ 单号:{1} ] 已成功，请耐心等待到账。”
        // 注意订单号只发后6位
        String email = iMemberService.getById(order.getUserId()).getEmail();
        if (StringUtils.isNotEmpty(email)) {
            MailConfig mailConfig = mailConfigService.getById(1L);
            SendMailVO sendMailVO = new SendMailVO();
            sendMailVO.setTitle("订单已经退款");
            sendMailVO.setContent(order.getOrderSn().substring(8, 14));
            sendMailVO.setSendMail(email);
            MailUtil.sendMail(mailConfig, sendMailVO, false);
        }

        // logHelper.logOrderSucceed("退款", "订单编号 " + order.getOrderSn());
        return R.success();
    }
}
