package com.shf.service.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.*;
import com.alipay.api.AlipayConfig;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.shf.common.constants.*;
import com.shf.common.model.user.*;
import com.shf.common.vo.*;
import com.shf.common.exception.CrmebException;
import com.shf.common.model.combination.StoreCombination;
import com.shf.common.model.combination.StorePink;
import com.shf.common.model.coupon.StoreCouponUser;
import com.shf.common.model.order.StoreOrder;
import com.shf.common.model.order.StoreOrderInfo;
import com.shf.common.model.product.StoreProduct;
import com.shf.common.model.product.StoreProductAttrValue;
import com.shf.common.model.product.StoreProductCoupon;
import com.shf.common.model.sms.SmsTemplate;
import com.shf.common.model.system.SystemAdmin;
import com.shf.common.model.system.SystemNotification;
import com.shf.common.request.OrderPayRequest;
import com.shf.common.response.OrderPayResultResponse;
import com.shf.common.utils.CrmebUtil;
import com.shf.common.utils.DateUtil;
import com.shf.common.utils.RedisUtil;
import com.shf.common.utils.WxPayUtil;
import com.shf.service.delete.OrderUtils;
import com.shf.service.service.*;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * OrderPayService ?????????
 */
@Data
@Service
public class OrderPayServiceImpl implements OrderPayService {
    private static final Logger logger = LoggerFactory.getLogger(OrderPayServiceImpl.class);

    @Autowired
    private StoreOrderService storeOrderService;

    @Autowired
    private StoreOrderStatusService storeOrderStatusService;

    @Autowired
    private StoreOrderInfoService storeOrderInfoService;

    @Lazy
    @Autowired
    private WeChatPayService weChatPayService;

    @Autowired
    private TemplateMessageService templateMessageService;

    @Autowired
    private UserBillService userBillService;

    @Lazy
    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private StoreProductCouponService storeProductCouponService;

    @Autowired
    private StoreCouponUserService storeCouponUserService;

    @Autowired
    private OrderUtils orderUtils;

    //?????????
    private StoreOrder order;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private StoreProductService storeProductService;

    @Autowired
    private UserLevelService userLevelService;

    @Autowired
    private StoreBargainService storeBargainService;

    @Autowired
    private StoreBargainUserService storeBargainUserService;

    @Autowired
    private StoreCombinationService storeCombinationService;

    @Autowired
    private StorePinkService storePinkService;

    @Autowired
    private UserBrokerageRecordService userBrokerageRecordService;

    @Autowired
    private StoreCouponService storeCouponService;

    @Autowired
    private SystemAdminService systemAdminService;

    @Autowired
    private UserTokenService userTokenService;

    @Autowired
    private UserIntegralRecordService userIntegralRecordService;

    @Autowired
    private StoreProductAttrValueService storeProductAttrValueService;

    @Autowired
    private WechatNewService wechatNewService;

    @Autowired
    private UserExperienceRecordService userExperienceRecordService;

    @Autowired
    private YlyPrintService ylyPrintService;

    @Autowired
    private SystemNotificationService systemNotificationService;

    @Autowired
    private SmsTemplateService smsTemplateService;

    @Resource
    private AliPayService aliPayService;

    @Resource
    private Environment config;

    /**
     * ??????????????????
     * @param storeOrder ??????
     */
    @Override
    public Boolean paySuccess(StoreOrder storeOrder) {

        User user = userService.getById(storeOrder.getUid());

        List<UserBill> billList = CollUtil.newArrayList();
        List<UserIntegralRecord> integralList = CollUtil.newArrayList();

        // ??????????????????
        UserBill userBill = userBillInit(storeOrder, user);
        billList.add(userBill);

        // ??????????????????
        if (storeOrder.getUseIntegral() > 0) {
            UserIntegralRecord integralRecordSub = integralRecordSubInit(storeOrder, user);
            integralList.add(integralRecordSub);
        }

        // ???????????????1.???????????????2.????????????
        Integer experience;
        experience = storeOrder.getPayPrice().setScale(0, BigDecimal.ROUND_DOWN).intValue();
        user.setExperience(user.getExperience() + experience);
        // ??????????????????
        UserExperienceRecord experienceRecord = experienceRecordInit(storeOrder, user.getExperience(), experience);


        // ???????????????1.?????????????????????2.??????????????????
        int integral;
        // ??????????????????
        //??????????????????
        String integralStr = systemConfigService.getValueByKey(Constants.CONFIG_KEY_INTEGRAL_RATE_ORDER_GIVE);
        if (StrUtil.isNotBlank(integralStr) && storeOrder.getPayPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal integralBig = new BigDecimal(integralStr);
            integral = integralBig.multiply(storeOrder.getPayPrice()).setScale(0, BigDecimal.ROUND_DOWN).intValue();
            if (integral > 0) {
                // ??????????????????
                UserIntegralRecord integralRecord = integralRecordInit(storeOrder, user.getIntegral(), integral, "order");
                integralList.add(integralRecord);
            }
        }

        // ??????????????????
        // ??????????????????
        // ??????????????????????????????
        List<StoreOrderInfo> orderInfoList = storeOrderInfoService.getListByOrderNo(storeOrder.getOrderId());
        if (orderInfoList.get(0).getProductType().equals(0)) {
            List<Integer> productIds = orderInfoList.stream().map(StoreOrderInfo::getProductId).collect(Collectors.toList());
            if (productIds.size() > 0) {
                List<StoreProduct> products = storeProductService.getListInIds(productIds);
                int sumIntegral = products.stream().mapToInt(StoreProduct::getGiveIntegral).sum();
                if (sumIntegral > 0) {
                    // ??????????????????
                    UserIntegralRecord integralRecord = integralRecordInit(storeOrder, user.getIntegral(), sumIntegral, "product");
                    integralList.add(integralRecord);
                }
            }
        }

        // ????????????????????????
        user.setPayCount(user.getPayCount() + 1);

        /**
         * ?????????????????????????????????
         */
        List<UserBrokerageRecord> recordList = assignCommission(storeOrder);

        // ???????????????
        if (!user.getIsPromoter()) {
            String funcStatus = systemConfigService.getValueByKey(SysConfigConstants.CONFIG_KEY_BROKERAGE_FUNC_STATUS);
            if (funcStatus.equals("1")) {
                String broQuota = systemConfigService.getValueByKey(SysConfigConstants.CONFIG_KEY_STORE_BROKERAGE_QUOTA);
                if (!broQuota.equals("-1") && storeOrder.getPayPrice().compareTo(new BigDecimal(broQuota)) >= 0) {// -1 ??????????????????
                    user.setIsPromoter(true);
                    user.setPromoterTime(cn.hutool.core.date.DateUtil.date());
                }
            }
        }

        Boolean execute = transactionTemplate.execute(e -> {
            //????????????
            storeOrderStatusService.createLog(storeOrder.getId(), Constants.ORDER_LOG_PAY_SUCCESS, Constants.ORDER_LOG_MESSAGE_PAY_SUCCESS);

            // ??????????????????
            userService.updateById(user);

            //????????????
            userBillService.saveBatch(billList);

            // ????????????
            userIntegralRecordService.saveBatch(integralList);

            // ????????????
            userExperienceRecordService.save(experienceRecord);

            //????????????
            userLevelService.upLevel(user);

            // ????????????
            if (CollUtil.isNotEmpty(recordList)) {
                recordList.forEach(temp -> {
                    temp.setLinkId(storeOrder.getOrderId());
                });
                userBrokerageRecordService.saveBatch(recordList);
            }

            // ?????????????????????????????????????????????
            if (storeOrder.getCombinationId() > 0) {
                pinkProcessing(storeOrder);
            }
            return Boolean.TRUE;
        });

        if (execute) {
            try {
                SystemNotification payNotification = systemNotificationService.getByMark(NotifyConstants.PAY_SUCCESS_MARK);
                // ????????????
                if (StrUtil.isNotBlank(user.getPhone()) && payNotification.getIsSms().equals(1)) {
                    SmsTemplate smsTemplate = smsTemplateService.getDetail(payNotification.getSmsId());
                    smsService.sendPaySuccess(user.getPhone(), storeOrder.getOrderId(), storeOrder.getPayPrice(), Integer.valueOf(smsTemplate.getTempId()));
                }

                // ?????????????????????????????????????????????
                SystemNotification payAdminNotification = systemNotificationService.getByMark(NotifyConstants.PAY_SUCCESS_ADMIN_MARK);
                if (payAdminNotification.getIsSms().equals(1)) {
                    // ????????????????????????????????????
                    List<SystemAdmin> systemAdminList = systemAdminService.findIsSmsList();
                    if (CollUtil.isNotEmpty(systemAdminList)) {
                        SmsTemplate smsTemplate = smsTemplateService.getDetail(payAdminNotification.getSmsId());
                        // ????????????
                        systemAdminList.forEach(admin -> {
                            smsService.sendOrderPaySuccessNotice(admin.getPhone(), storeOrder.getOrderId(), admin.getRealName(), Integer.valueOf(smsTemplate.getTempId()));
                        });
                    }
                }

                if (payNotification.getIsWechat().equals(1) || payNotification.getIsRoutine().equals(1)) {
                    //??????????????????
                    pushMessageOrder(storeOrder, user, payNotification);
                }

                // ???????????????????????????????????????
                autoSendCoupons(storeOrder);

                // ???????????? ????????????
                ylyPrintService.YlyPrint(storeOrder.getOrderId(),true);

            } catch (Exception e) {
                e.printStackTrace();
                logger.error("??????????????????????????????????????????????????????");
            }
        }
        return execute;
    }

    // ??????????????????????????????
    private Boolean pinkProcessing(StoreOrder storeOrder) {
        // ????????????????????????
        StorePink storePink = storePinkService.getById(storeOrder.getPinkId());
        if (storePink.getKId() <= 0) {
            return true;
        }

        List<StorePink> pinkList = storePinkService.getListByCidAndKid(storePink.getCid(), storePink.getKId());
        StorePink tempPink = storePinkService.getById(storePink.getKId());
        pinkList.add(tempPink);
        if (pinkList.size() < storePink.getPeople()) {// ??????????????????
            return true;
        }
        // 1.??????????????????
        // 2.?????????????????????????????????????????????
        pinkList.forEach(e -> {
            e.setStatus(2);
        });
        boolean update = storePinkService.updateBatchById(pinkList);
        if (!update) {
            logger.error("???????????????????????????????????????????????????,orderNo = " + storeOrder.getOrderId());
            return false;
        }
        SystemNotification notification = systemNotificationService.getByMark(NotifyConstants.GROUP_SUCCESS_MARK);
        if (notification.getIsWechat().equals(1) || notification.getIsRoutine().equals(1)) {
            pinkList.forEach(i -> {
                StoreOrder order = storeOrderService.getByOderId(i.getOrderId());
                StoreCombination storeCombination = storeCombinationService.getById(i.getCid());
                User tempUser = userService.getById(i.getUid());
                // ????????????????????????
                MyRecord record = new MyRecord();
                record.set("orderNo", order.getOrderId());
                record.set("proName", storeCombination.getTitle());
                record.set("payType", order.getPayType());
                record.set("isChannel", order.getIsChannel());
                pushMessagePink(record, tempUser, notification);
            });
        }
        return true;
    }

    /**
     * ????????????????????????
     * @param record ????????????
     * @param user ??????
     */
    private void pushMessagePink(MyRecord record, User user, SystemNotification notification) {
        if (!record.getStr("payType").equals(Constants.PAY_TYPE_WE_CHAT)) {
            return ;
        }
        if (record.getInt("isChannel").equals(2)) {
            return ;
        }

        UserToken userToken;
        HashMap<String, String> temMap = new HashMap<>();
        // ?????????
        if (record.getInt("isChannel").equals(Constants.ORDER_PAY_CHANNEL_PUBLIC) && notification.getIsWechat().equals(1)) {
            userToken = userTokenService.getTokenByUserId(user.getUid(), UserConstants.USER_TOKEN_TYPE_WECHAT);
            if (ObjectUtil.isNull(userToken)) {
                return ;
            }
            // ????????????????????????
            temMap.put(Constants.WE_CHAT_TEMP_KEY_FIRST, "??????????????????????????????????????????????????????");
            temMap.put("keyword1", record.getStr("orderNo"));
            temMap.put("keyword2", record.getStr("proName"));
            temMap.put(Constants.WE_CHAT_TEMP_KEY_END, "?????????????????????");
            templateMessageService.pushTemplateMessage(notification.getWechatId(), temMap, userToken.getToken());
        } else if (notification.getIsRoutine().equals(1)) {
            // ???????????????????????????
            userToken = userTokenService.getTokenByUserId(user.getUid(), UserConstants.USER_TOKEN_TYPE_ROUTINE);
            if (ObjectUtil.isNull(userToken)) {
                return ;
            }
            // ????????????
//        temMap.put("character_string1",  record.getStr("orderNo"));
//        temMap.put("thing2", record.getStr("proName"));
//        temMap.put("thing5", "??????????????????????????????????????????????????????");
            temMap.put("character_string10",  record.getStr("orderNo"));
            temMap.put("thing7", record.getStr("proName"));
            temMap.put("thing9", "??????????????????????????????????????????????????????");
            templateMessageService.pushMiniTemplateMessage(notification.getRoutineId(), temMap, userToken.getToken());
        }

    }

    /**
     * ????????????
     * @param storeOrder ??????
     * @return List<UserBrokerageRecord>
     */
    private List<UserBrokerageRecord> assignCommission(StoreOrder storeOrder) {
        // ????????????????????????????????????
        String isOpen = systemConfigService.getValueByKey(Constants.CONFIG_KEY_STORE_BROKERAGE_IS_OPEN);
        if(StrUtil.isBlank(isOpen) || isOpen.equals("0")){
            return CollUtil.newArrayList();
        }
        // ?????????????????????
        if(storeOrder.getCombinationId() > 0 || storeOrder.getSeckillId() > 0 || storeOrder.getBargainId() > 0){
            return CollUtil.newArrayList();
        }
        // ???????????????????????????
        User user = userService.getById(storeOrder.getUid());
        // ????????????????????? ???????????? ?????? ???????????????????????????  ????????????
        if(null == user.getSpreadUid() || user.getSpreadUid() < 1 || user.getSpreadUid().equals(storeOrder.getUid())){
            return CollUtil.newArrayList();
        }
        // ????????????????????????????????????
        List<MyRecord> spreadRecordList = getSpreadRecordList(user.getSpreadUid());
        if (CollUtil.isEmpty(spreadRecordList)) {
            return CollUtil.newArrayList();
        }
        // ?????????????????????
        String fronzenTime = systemConfigService.getValueByKey(Constants.CONFIG_KEY_STORE_BROKERAGE_EXTRACT_TIME);

        // ??????????????????
        List<UserBrokerageRecord> brokerageRecordList = spreadRecordList.stream().map(record -> {
            BigDecimal brokerage = calculateCommission(record, storeOrder.getId());
            UserBrokerageRecord brokerageRecord = new UserBrokerageRecord();
            brokerageRecord.setUid(record.getInt("spreadUid"));
            brokerageRecord.setLinkType(BrokerageRecordConstants.BROKERAGE_RECORD_LINK_TYPE_ORDER);
            brokerageRecord.setType(BrokerageRecordConstants.BROKERAGE_RECORD_TYPE_ADD);
            brokerageRecord.setTitle(BrokerageRecordConstants.BROKERAGE_RECORD_TITLE_ORDER);
            brokerageRecord.setPrice(brokerage);
            brokerageRecord.setMark(StrUtil.format("???????????????????????????{}", brokerage));
            brokerageRecord.setStatus(BrokerageRecordConstants.BROKERAGE_RECORD_STATUS_CREATE);
            brokerageRecord.setFrozenTime(Integer.valueOf(Optional.ofNullable(fronzenTime).orElse("0")));
            brokerageRecord.setCreateTime(DateUtil.nowDateTime());
            brokerageRecord.setBrokerageLevel(record.getInt("index"));
            return brokerageRecord;
        }).collect(Collectors.toList());

        return brokerageRecordList;
    }

    /**
     * ????????????
     * @param record index-???????????????spreadUid-?????????
     * @param orderId ??????id
     * @return BigDecimal
     */
    private BigDecimal calculateCommission(MyRecord record, Integer orderId) {
        BigDecimal brokeragePrice = BigDecimal.ZERO;
        // ??????????????????
        List<StoreOrderInfoOldVo> orderInfoVoList = storeOrderInfoService.getOrderListByOrderId(orderId);
        if (CollUtil.isEmpty(orderInfoVoList)) {
            return brokeragePrice;
        }
        BigDecimal totalBrokerPrice = BigDecimal.ZERO;
        //?????????????????????????????????
        Integer index = record.getInt("index");
        String key = "";
        if (index == 1) {
            key = Constants.CONFIG_KEY_STORE_BROKERAGE_RATE_ONE;
        }
        if (index == 2) {
            key = Constants.CONFIG_KEY_STORE_BROKERAGE_RATE_TWO;
        }
        String rate = systemConfigService.getValueByKey(key);
        if(StringUtils.isBlank(rate)){
            rate = "1";
        }
        //??????????????????????????? ??????80??? ?????????????????????????????? 10*10
        BigDecimal rateBigDecimal = brokeragePrice;
        if(StringUtils.isNotBlank(rate)){
            rateBigDecimal = new BigDecimal(rate).divide(BigDecimal.TEN.multiply(BigDecimal.TEN));
        }

        for (StoreOrderInfoOldVo orderInfoVo : orderInfoVoList) {
            // ?????????????????????????????????
            StoreProductAttrValue attrValue = storeProductAttrValueService.getById(orderInfoVo.getInfo().getAttrValueId());
            if (orderInfoVo.getInfo().getIsSub()) {// ???????????????
                if(index == 1){
                    brokeragePrice = Optional.ofNullable(attrValue.getBrokerage()).orElse(BigDecimal.ZERO);
                }
                if(index == 2){
                    brokeragePrice = Optional.ofNullable(attrValue.getBrokerageTwo()).orElse(BigDecimal.ZERO);
                }
            } else {// ????????????
                if(!rateBigDecimal.equals(BigDecimal.ZERO)){
                    // ????????????????????????, ??????????????????????????????????????????
                    // ???????????????????????????
                    if (ObjectUtil.isNotNull(orderInfoVo.getInfo().getVipPrice())) {
                        brokeragePrice = orderInfoVo.getInfo().getVipPrice().multiply(rateBigDecimal).setScale(2, BigDecimal.ROUND_DOWN);
                    } else {
                        brokeragePrice = orderInfoVo.getInfo().getPrice().multiply(rateBigDecimal).setScale(2, BigDecimal.ROUND_DOWN);
                    }
                } else {
                    brokeragePrice = BigDecimal.ZERO;
                }
            }
            // ??????????????????????????????
            if (brokeragePrice.compareTo(BigDecimal.ZERO) > 0 && orderInfoVo.getInfo().getPayNum() > 1) {
                brokeragePrice = brokeragePrice.multiply(new BigDecimal(orderInfoVo.getInfo().getPayNum()));
            }
            totalBrokerPrice = totalBrokerPrice.add(brokeragePrice);
        }

        return totalBrokerPrice;
    }

    /**
     * ????????????????????????????????????
     * @param spreadUid ???????????????Uid
     * @return List<MyRecord>
     */
    private List<MyRecord> getSpreadRecordList(Integer spreadUid) {
        List<MyRecord> recordList = CollUtil.newArrayList();

        // ?????????
        User spreadUser = userService.getById(spreadUid);
        if (ObjectUtil.isNull(spreadUser)) {
            return recordList;
        }
        // ??????????????????
        String model = systemConfigService.getValueByKey(Constants.CONFIG_KEY_STORE_BROKERAGE_MODEL);
        if (StrUtil.isNotBlank(model) && model.equals("1") && !spreadUser.getIsPromoter()) {
            // ??????????????????????????????????????????????????????
            return recordList;
        }
        MyRecord firstRecord = new MyRecord();
        firstRecord.set("index", 1);
        firstRecord.set("spreadUid", spreadUid);
        recordList.add(firstRecord);

        // ?????????
        User spreadSpreadUser = userService.getById(spreadUser.getSpreadUid());
        if (ObjectUtil.isNull(spreadSpreadUser)) {
            return recordList;
        }
        if (StrUtil.isNotBlank(model) && model.equals("1") && !spreadSpreadUser.getIsPromoter()) {
            // ??????????????????????????????????????????????????????
            return recordList;
        }
        MyRecord secondRecord = new MyRecord();
        secondRecord.set("index", 2);
        secondRecord.set("spreadUid", spreadSpreadUser.getUid());
        recordList.add(secondRecord);
        return recordList;
    }

    /**
     * ????????????
     * @param storeOrder ??????
     * @return Boolean Boolean
     */
    private Boolean yuePay(StoreOrder storeOrder) {

        // ??????????????????
        User user = userService.getById(storeOrder.getUid());
        if (ObjectUtil.isNull(user)) {
            throw new CrmebException("???????????????");
        }
        if (user.getNowMoney().compareTo(storeOrder.getPayPrice()) < 0) {
            throw new CrmebException("??????????????????");
        }
        if (user.getIntegral() < storeOrder.getUseIntegral()) {
            throw new CrmebException("??????????????????");
        }
        storeOrder.setPaid(true);
        storeOrder.setPayTime(DateUtil.nowDateTime());
        Boolean execute = transactionTemplate.execute(e -> {
            // ????????????
            storeOrderService.updateById(storeOrder);
            // ???????????????????????????????????????task?????????
            userService.updateNowMoney(user, storeOrder.getPayPrice(), "sub");
            // ????????????
            if (storeOrder.getUseIntegral() > 0) {
                userService.updateIntegral(user, storeOrder.getUseIntegral(), "sub");
            }
            // ??????????????????redis??????
            redisUtil.lPush(TaskConstants.ORDER_TASK_PAY_SUCCESS_AFTER, storeOrder.getOrderId());

            // ????????????
            if (storeOrder.getCombinationId() > 0) {
                // ??????????????????????????????
                StorePink headPink = new StorePink();
                Integer pinkId = storeOrder.getPinkId();
                if (pinkId > 0) {
                    headPink = storePinkService.getById(pinkId);
                    if (ObjectUtil.isNull(headPink) || headPink.getIsRefund().equals(true) || headPink.getStatus() == 3) {
                        pinkId = 0;
                    }
                }
                StoreCombination storeCombination = storeCombinationService.getById(storeOrder.getCombinationId());
                // ???????????????????????????????????????
                if (pinkId > 0) {
                    Integer count = storePinkService.getCountByKid(pinkId);
                    if (count >= storeCombination.getPeople()) {
                        pinkId = 0;
                    }
                }
                // ?????????????????????
                StorePink storePink = new StorePink();
                storePink.setUid(user.getUid());
                storePink.setAvatar(user.getAvatar());
                storePink.setNickname(user.getNickname());
                storePink.setOrderId(storeOrder.getOrderId());
                storePink.setOrderIdKey(storeOrder.getId());
                storePink.setTotalNum(storeOrder.getTotalNum());
                storePink.setTotalPrice(storeOrder.getTotalPrice());
                storePink.setCid(storeCombination.getId());
                storePink.setPid(storeCombination.getProductId());
                storePink.setPeople(storeCombination.getPeople());
                storePink.setPrice(storeCombination.getPrice());
                Integer effectiveTime = storeCombination.getEffectiveTime();// ???????????????
                DateTime dateTime = cn.hutool.core.date.DateUtil.date();
                storePink.setAddTime(dateTime.getTime());
                if (pinkId > 0) {
                    storePink.setStopTime(headPink.getStopTime());
                } else {
                    DateTime hourTime = cn.hutool.core.date.DateUtil.offsetHour(dateTime, effectiveTime);
                    long stopTime =  hourTime.getTime();
                    if (stopTime > storeCombination.getStopTime()) {
                        stopTime = storeCombination.getStopTime();
                    }
                    storePink.setStopTime(stopTime);
                }
                storePink.setKId(pinkId);
                storePink.setIsTpl(false);
                storePink.setIsRefund(false);
                storePink.setStatus(1);
                storePinkService.save(storePink);
                // ??????????????????????????????????????????
                storeOrder.setPinkId(storePink.getId());
                storeOrderService.updateById(storeOrder);
            }

            return Boolean.TRUE;
        });
        if (!execute) {
            throw new CrmebException("????????????????????????");
        }
        return execute;
    }

    /**
     * ????????????
     * @param orderPayRequest   ????????????
     * @param ip                ip
     * @return OrderPayResultResponse
     * 1.????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     * 2.???????????????????????????????????????????????????????????????task
     */
    @Override
    public OrderPayResultResponse payment(OrderPayRequest orderPayRequest, String ip) {
        StoreOrder storeOrder = storeOrderService.getByOderId(orderPayRequest.getOrderNo());
        if (ObjectUtil.isNull(storeOrder)) {
            throw new CrmebException("???????????????");
        }
        if (storeOrder.getIsDel()) {
            throw new CrmebException("??????????????????");
        }
        if (storeOrder.getPaid()) {
            throw new CrmebException("???????????????");
        }
        User user = userService.getById(storeOrder.getUid());
        if (ObjectUtil.isNull(user)) {
            throw new CrmebException("???????????????");
        }

        // ?????????????????????????????????????????????
        if (!storeOrder.getPayType().equals(orderPayRequest.getPayType())) {
            // ??????????????????????????????,??????????????????
            storeOrder.setPayType(orderPayRequest.getPayType());
            // ????????????
            if (orderPayRequest.getPayType().equals(PayConstants.PAY_TYPE_YUE)) {
                if (user.getNowMoney().compareTo(storeOrder.getPayPrice()) < 0) {
                    throw new CrmebException("??????????????????");
                }
                storeOrder.setIsChannel(3);
            }
            if (orderPayRequest.getPayType().equals(PayConstants.PAY_TYPE_WE_CHAT)) {
                switch (orderPayRequest.getPayChannel()){
                    case PayConstants.PAY_CHANNEL_WE_CHAT_H5:// H5
                        storeOrder.setIsChannel(2);
                        break;
                    case PayConstants.PAY_CHANNEL_WE_CHAT_PUBLIC:// ?????????
                        storeOrder.setIsChannel(0);
                        break;
                    case PayConstants.PAY_CHANNEL_WE_CHAT_PROGRAM:// ?????????
                        storeOrder.setIsChannel(1);
                        break;
                }
            }
            if (orderPayRequest.getPayType().equals(PayConstants.PAY_TYPE_ALI_PAY)) {
                switch (orderPayRequest.getPayChannel()){
                    case PayConstants.PAY_CHANNEL_ALI_PAY:// ???????????????
                        storeOrder.setIsChannel(6);
                        break;
                    case PayConstants.PAY_CHANNEL_ALI_APP_PAY:// ?????????app??????
                        storeOrder.setIsChannel(7);
                        break;
                }
            }

            boolean changePayType = storeOrderService.updateById(storeOrder);
            if (!changePayType) {
                throw new CrmebException("??????????????????????????????!");
            }
        }

        if (user.getIntegral() < storeOrder.getUseIntegral()) {
            throw new CrmebException("??????????????????");
        }

        OrderPayResultResponse response = new OrderPayResultResponse();
        response.setOrderNo(storeOrder.getOrderId());
        response.setPayType(storeOrder.getPayType());
        // 0??????
        if (storeOrder.getPayPrice().compareTo(BigDecimal.ZERO) <= 0) {
            Boolean aBoolean = yuePay(storeOrder);
            response.setPayType(PayConstants.PAY_TYPE_YUE);
            response.setStatus(aBoolean);
            return response;
        }

        // ??????????????????????????????????????????????????????????????????????????????
        if (storeOrder.getPayType().equals(PayConstants.PAY_TYPE_WE_CHAT)) {
            // ?????????
            Map<String, String> unifiedorder = unifiedorder(storeOrder, ip);
            response.setStatus(true);
            WxPayJsResultVo vo = new WxPayJsResultVo();
            vo.setAppId(unifiedorder.get("appId"));
            vo.setNonceStr(unifiedorder.get("nonceStr"));
            vo.setPackages(unifiedorder.get("package"));
            vo.setSignType(unifiedorder.get("signType"));
            vo.setTimeStamp(unifiedorder.get("timeStamp"));
            vo.setPaySign(unifiedorder.get("paySign"));
            if (storeOrder.getIsChannel() == 2) {
                vo.setMwebUrl(unifiedorder.get("mweb_url"));
                response.setPayType(PayConstants.PAY_CHANNEL_WE_CHAT_H5);
            }
            if (storeOrder.getIsChannel() == 4 || storeOrder.getIsChannel() == 5) {
                vo.setPartnerid(unifiedorder.get("partnerid"));
            }
            // ?????????????????????
            storeOrder.setOutTradeNo(unifiedorder.get("outTradeNo"));
            storeOrderService.updateById(storeOrder);
            response.setJsConfig(vo);
            return response;
        }

//        ???????????????
        if (storeOrder.getPayType().equals(PayConstants.PAY_TYPE_ALI_PAY)) {
            String appId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_APP_ID);
            String sellerId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_SELLER_ID);
            String gatewayUrl = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_GATEWAY_URL);
            String merchantPrivateKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_MERCHANT_PRIVATE_KEY);
            String publicKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_PUBLIC_KEY);
            String contentKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_CONTENT_KEY);
            String returnUrl = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_RETURN_URL);
            String notifyUrl = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_NOTIFY_URL);

            AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
            request.setNotifyUrl(notifyUrl);
            request.setReturnUrl(returnUrl);
            JSONObject bizContent = new JSONObject();
            bizContent.put("out_trade_no", storeOrder.getOrderId());
            bizContent.put("total_amount", storeOrder.getPayPrice());
            bizContent.put("subject", storeOrder.getOrderId());
            bizContent.put("product_code", "QUICK_WAP_WAY");
            request.setBizContent(bizContent.toString());
            AlipayTradeWapPayResponse res = null;
            try {
               AlipayConfig alipayConfig = new AlipayConfig();
                //??????????????????
                alipayConfig.setServerUrl(gatewayUrl);
                //????????????Id
                alipayConfig.setAppId(appId);
                //??????????????????
                alipayConfig.setPrivateKey(merchantPrivateKey);
                //??????????????????????????????json
                alipayConfig.setFormat(AlipayConstants.FORMAT_JSON);
                //???????????????
                alipayConfig.setCharset(AlipayConstants.CHARSET_UTF8);
                //?????????????????????
                alipayConfig.setAlipayPublicKey(publicKey);
                //??????????????????
                alipayConfig.setSignType(AlipayConstants.SIGN_TYPE_RSA2);
                //??????client
                AlipayClient alipayClient = new DefaultAlipayClient(alipayConfig);

                res = alipayClient.pageExecute(request);
            } catch (AlipayApiException e) {
                throw new CrmebException("????????????????????????");
            }
            response.setStatus(true);
            System.out.println(res.getBody());
            response.setAlipayRequest(res.getBody());
            return response;
        }

        // ????????????
        if (storeOrder.getPayType().equals(PayConstants.PAY_TYPE_YUE)) {
            Boolean yueBoolean = yuePay(storeOrder);
            response.setStatus(yueBoolean);
            return response;
        }
        if (storeOrder.getPayType().equals(PayConstants.PAY_TYPE_OFFLINE)) {
            throw new CrmebException("???????????????????????????");
        }
        response.setStatus(false);
        return response;
    }


    /**
     * ?????????
     * @param storeOrder ??????
     * @param ip ip
     * @return ?????????????????????
     */
    private Map<String, String> unifiedorder(StoreOrder storeOrder, String ip) {
        // ????????????openId
        // ????????????????????????????????????????????????openId???????????????openId
        UserToken userToken = new UserToken();
        if (storeOrder.getIsChannel() == 0) {// ?????????
            userToken = userTokenService.getTokenByUserId(storeOrder.getUid(), 1);
        }
        if (storeOrder.getIsChannel() == 1) {// ?????????
            userToken = userTokenService.getTokenByUserId(storeOrder.getUid(), 2);
        }
        if (storeOrder.getIsChannel() == 2) {// H5 ??????
            userToken.setToken("");
        }
        if (storeOrder.getIsChannel() == 6) {// H5 ?????????
            userToken.setToken("");
        }
        if (ObjectUtil.isNull(userToken)) {
            throw new CrmebException("???????????????openId");
        }

        // ??????appid???mch_id
        // ????????????key
        String appId = "";
        String mchId = "";
        String signKey = "";
        if (storeOrder.getIsChannel() == 0) {// ?????????
            appId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_WE_CHAT_APP_ID);
            mchId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_WE_CHAT_MCH_ID);
            signKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_WE_CHAT_APP_KEY);
        }
        if (storeOrder.getIsChannel() == 1) {// ?????????
            appId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ROUTINE_APP_ID);
            mchId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ROUTINE_MCH_ID);
            signKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ROUTINE_APP_KEY);
        }
        if (storeOrder.getIsChannel() == 2) {// H5,??????????????????
            appId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_WE_CHAT_APP_ID);
            mchId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_WE_CHAT_MCH_ID);
            signKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_WE_CHAT_APP_KEY);
        }

        // ???????????????????????????
        CreateOrderRequestVo unifiedorderVo = getUnifiedorderVo(storeOrder, userToken.getToken(), ip, appId, mchId, signKey);
        // ???????????????????????????
        CreateOrderResponseVo responseVo = wechatNewService.payUnifiedorder(unifiedorderVo);
        // ???????????????????????????
        Map<String, String> map = new HashMap<>();
        map.put("appId", unifiedorderVo.getAppid());
        map.put("nonceStr", unifiedorderVo.getAppid());
        map.put("package", "prepay_id=".concat(responseVo.getPrepayId()));
        map.put("signType", unifiedorderVo.getSign_type());
        Long currentTimestamp = WxPayUtil.getCurrentTimestamp();
        map.put("timeStamp", Long.toString(currentTimestamp));
        String paySign = WxPayUtil.getSign(map, signKey);
        map.put("paySign", paySign);
        map.put("prepayId", responseVo.getPrepayId());
        map.put("prepayTime", DateUtil.nowDateTimeStr());
        map.put("outTradeNo", unifiedorderVo.getOut_trade_no());
        if (storeOrder.getIsChannel() == 2) {
            map.put("mweb_url", responseVo.getMWebUrl());
        }
        return map;
    }

    /**
     * ???????????????????????????
     * @return ?????????????????????
     */
    private CreateOrderRequestVo getUnifiedorderVo(StoreOrder storeOrder, String openid, String ip, String appId, String mchId, String signKey) {

        // ????????????
        String domain = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_SITE_URL);
        String apiDomain = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_API_URL);

        AttachVo attachVo = new AttachVo(Constants.SERVICE_PAY_TYPE_ORDER, storeOrder.getUid());
        CreateOrderRequestVo vo = new CreateOrderRequestVo();

        vo.setAppid(appId);
        vo.setMch_id(mchId);
        vo.setNonce_str(WxPayUtil.getNonceStr());
        vo.setSign_type(PayConstants.WX_PAY_SIGN_TYPE_MD5);
        String siteName = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_SITE_NAME);
        // ??????????????????????????????????????????????????????
        vo.setBody(siteName);
        vo.setAttach(JSONObject.toJSONString(attachVo));
        vo.setOut_trade_no(CrmebUtil.getOrderNo("wxNo"));
        // ?????????????????????BigDecimal,???????????????Integer??????
        vo.setTotal_fee(storeOrder.getPayPrice().multiply(BigDecimal.TEN).multiply(BigDecimal.TEN).intValue());
        vo.setSpbill_create_ip(ip);
        vo.setNotify_url(apiDomain + PayConstants.WX_PAY_NOTIFY_API_URI);
        vo.setTrade_type(PayConstants.WX_PAY_TRADE_TYPE_JS);
        vo.setOpenid(openid);
        if (storeOrder.getIsChannel() == 2){// H5
            vo.setTrade_type(PayConstants.WX_PAY_TRADE_TYPE_H5);
            vo.setOpenid(null);
        }
        CreateOrderH5SceneInfoVo createOrderH5SceneInfoVo = new CreateOrderH5SceneInfoVo(
                new CreateOrderH5SceneInfoDetailVo(
                        domain,
                        systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_SITE_NAME)
                )
        );
        vo.setScene_info(JSONObject.toJSONString(createOrderH5SceneInfoVo));
        String sign = WxPayUtil.getSign(vo, signKey);
        vo.setSign(sign);
        return vo;
    }

    private UserIntegralRecord integralRecordSubInit(StoreOrder storeOrder, User user) {
        UserIntegralRecord integralRecord = new UserIntegralRecord();
        integralRecord.setUid(storeOrder.getUid());
        integralRecord.setLinkId(storeOrder.getOrderId());
        integralRecord.setLinkType(IntegralRecordConstants.INTEGRAL_RECORD_LINK_TYPE_ORDER);
        integralRecord.setType(IntegralRecordConstants.INTEGRAL_RECORD_TYPE_SUB);
        integralRecord.setTitle(IntegralRecordConstants.BROKERAGE_RECORD_TITLE_ORDER);
        integralRecord.setIntegral(storeOrder.getUseIntegral());
        integralRecord.setBalance(user.getIntegral());
        integralRecord.setMark(StrUtil.format("??????????????????{}??????????????????", storeOrder.getUseIntegral()));
        integralRecord.setStatus(IntegralRecordConstants.INTEGRAL_RECORD_STATUS_COMPLETE);
        return integralRecord;
    }

    private UserBill userBillInit(StoreOrder order, User user) {
        UserBill userBill = new UserBill();
        userBill.setPm(0);
        userBill.setUid(order.getUid());
        userBill.setLinkId(order.getId().toString());
        userBill.setTitle("????????????");
        userBill.setCategory(Constants.USER_BILL_CATEGORY_MONEY);
        userBill.setType(Constants.USER_BILL_TYPE_PAY_ORDER);
        userBill.setNumber(order.getPayPrice());
        userBill.setBalance(user.getNowMoney());
        userBill.setMark("??????" + order.getPayPrice() + "???????????????");
        return userBill;
    }

    /**
     * ??????????????????
     */
    private UserExperienceRecord experienceRecordInit(StoreOrder storeOrder, Integer balance, Integer experience) {
        UserExperienceRecord record = new UserExperienceRecord();
        record.setUid(storeOrder.getUid());
        record.setLinkId(storeOrder.getOrderId());
        record.setLinkType(ExperienceRecordConstants.EXPERIENCE_RECORD_LINK_TYPE_ORDER);
        record.setType(ExperienceRecordConstants.EXPERIENCE_RECORD_TYPE_ADD);
        record.setTitle(ExperienceRecordConstants.EXPERIENCE_RECORD_TITLE_ORDER);
        record.setExperience(experience);
        record.setBalance(balance);
        record.setMark("????????????????????????" + experience + "??????");
        record.setCreateTime(cn.hutool.core.date.DateUtil.date());
        return record;
    }

    /**
     * ??????????????????
     * @return UserIntegralRecord
     */
    private UserIntegralRecord integralRecordInit(StoreOrder storeOrder, Integer balance, Integer integral, String type) {
        UserIntegralRecord integralRecord = new UserIntegralRecord();
        integralRecord.setUid(storeOrder.getUid());
        integralRecord.setLinkId(storeOrder.getOrderId());
        integralRecord.setLinkType(IntegralRecordConstants.INTEGRAL_RECORD_LINK_TYPE_ORDER);
        integralRecord.setType(IntegralRecordConstants.INTEGRAL_RECORD_TYPE_ADD);
        integralRecord.setTitle(IntegralRecordConstants.BROKERAGE_RECORD_TITLE_ORDER);
        integralRecord.setIntegral(integral);
        integralRecord.setBalance(balance);
        if (type.equals("order")){
            integralRecord.setMark(StrUtil.format("??????????????????,????????????{}??????", integral));
        }
        if (type.equals("product")) {
            integralRecord.setMark(StrUtil.format("??????????????????,????????????{}??????", integral));
        }
        integralRecord.setStatus(IntegralRecordConstants.INTEGRAL_RECORD_STATUS_CREATE);
        // ?????????????????????
        String fronzenTime = systemConfigService.getValueByKey(Constants.CONFIG_KEY_STORE_INTEGRAL_EXTRACT_TIME);
        integralRecord.setFrozenTime(Integer.valueOf(Optional.ofNullable(fronzenTime).orElse("0")));
        integralRecord.setCreateTime(DateUtil.nowDateTime());
        return integralRecord;
    }

    /**
     * ??????????????????
     * ????????????????????????
     * ?????????????????????
     * ?????????????????????
     */
    private void pushMessageOrder(StoreOrder storeOrder, User user, SystemNotification payNotification) {
        if (storeOrder.getIsChannel().equals(2)) {// H5
            return;
        }
        UserToken userToken;
        HashMap<String, String> temMap = new HashMap<>();
        if (!storeOrder.getPayType().equals(Constants.PAY_TYPE_WE_CHAT)) {
            return;
        }
        // ?????????
        if (storeOrder.getIsChannel().equals(Constants.ORDER_PAY_CHANNEL_PUBLIC) && payNotification.getIsWechat().equals(1)) {
            userToken = userTokenService.getTokenByUserId(user.getUid(), UserConstants.USER_TOKEN_TYPE_WECHAT);
            if (ObjectUtil.isNull(userToken)) {
                return ;
            }
            // ????????????????????????
            temMap.put(Constants.WE_CHAT_TEMP_KEY_FIRST, "??????????????????????????????");
            temMap.put("keyword1", storeOrder.getPayPrice().toString());
            temMap.put("keyword2", storeOrder.getOrderId());
            temMap.put(Constants.WE_CHAT_TEMP_KEY_END, "?????????????????????");
            templateMessageService.pushTemplateMessage(payNotification.getWechatId(), temMap, userToken.getToken());
            return;
        }
        if (storeOrder.getIsChannel().equals(Constants.ORDER_PAY_CHANNEL_PROGRAM) && payNotification.getIsRoutine().equals(1)) {
            // ???????????????????????????
            userToken = userTokenService.getTokenByUserId(user.getUid(), UserConstants.USER_TOKEN_TYPE_ROUTINE);
            if (ObjectUtil.isNull(userToken)) {
                return ;
            }
            // ????????????
//            temMap.put("character_string1", storeOrder.getOrderId());
//            temMap.put("amount2", storeOrder.getPayPrice().toString() + "???");
//            temMap.put("thing7", "???????????????????????????");
            temMap.put("character_string3", storeOrder.getOrderId());
            temMap.put("amount9", storeOrder.getPayPrice().toString() + "???");
            temMap.put("thing6", "???????????????????????????");
            templateMessageService.pushMiniTemplateMessage(payNotification.getRoutineId(), temMap, userToken.getToken());
        }
    }

    /**
     * ?????????????????????????????????
     */
    private void autoSendCoupons(StoreOrder storeOrder){
        // ????????????????????????????????????
        List<StoreOrderInfoOldVo> orders = storeOrderInfoService.getOrderListByOrderId(storeOrder.getId());
        if(null == orders){
            return;
        }
        List<StoreCouponUser> couponUserList = CollUtil.newArrayList();
        Map<Integer, Boolean> couponMap = CollUtil.newHashMap();
        for (StoreOrderInfoOldVo order : orders) {
            List<StoreProductCoupon> couponsForGiveUser = storeProductCouponService.getListByProductId(order.getProductId());
            for (int i = 0; i < couponsForGiveUser.size();) {
                StoreProductCoupon storeProductCoupon = couponsForGiveUser.get(i);
                MyRecord record = storeCouponUserService.paySuccessGiveAway(storeProductCoupon.getIssueCouponId(), storeOrder.getUid());
                if (record.getStr("status").equals("fail")) {
                    logger.error(StrUtil.format("???????????????????????????????????????????????????{}", record.getStr("errMsg")));
                    couponsForGiveUser.remove(i);
                    continue;
                }

                StoreCouponUser storeCouponUser = record.get("storeCouponUser");
                couponUserList.add(storeCouponUser);
                couponMap.put(storeCouponUser.getCouponId(), record.getBoolean("isLimited"));
                i++;
            }
        }

        Boolean execute = transactionTemplate.execute(e -> {
            if (CollUtil.isNotEmpty(couponUserList)) {
                storeCouponUserService.saveBatch(couponUserList);
                couponUserList.forEach(i -> storeCouponService.deduction(i.getCouponId(), 1, couponMap.get(i.getCouponId())));
            }
            return Boolean.TRUE;
        });
        if (!execute) {
            logger.error(StrUtil.format("?????????????????????????????????????????????????????????????????????{}", storeOrder.getOrderId()));
        }
    }
}
