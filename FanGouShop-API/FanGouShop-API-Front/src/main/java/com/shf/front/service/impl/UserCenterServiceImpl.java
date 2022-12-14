package com.shf.front.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.*;
import com.alipay.api.AlipayConfig;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageInfo;
import com.shf.common.constants.*;
import com.shf.common.model.user.*;
import com.shf.common.request.*;
import com.shf.common.response.*;
import com.shf.common.utils.*;
import com.shf.common.vo.MyRecord;
import com.shf.common.vo.SystemGroupDataRechargeConfigVo;
import com.shf.common.vo.WeChatAuthorizeLoginUserInfoVo;
import com.shf.common.vo.WeChatMiniAuthorizeVo;
import com.shf.common.exception.CrmebException;
import com.shf.common.model.coupon.StoreCoupon;
import com.shf.common.model.coupon.StoreCouponUser;
import com.shf.common.model.finance.UserRecharge;
import com.shf.common.model.order.StoreOrder;
import com.shf.common.model.system.SystemUserLevel;
import com.shf.common.page.CommonPage;
import com.shf.common.token.FrontTokenComponent;
import com.shf.common.token.WeChatOauthToken;
import com.shf.front.service.LoginService;
import com.shf.service.service.*;
import com.shf.front.service.UserCenterService;
import com.shf.service.dao.UserDao;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ???????????? ???????????????
 */
@Service
public class UserCenterServiceImpl extends ServiceImpl<UserDao, User> implements UserCenterService {

    private final Logger logger = LoggerFactory.getLogger(UserCenterServiceImpl.class);

    @Autowired
    private UserService userService;

    @Autowired
    private UserBillService userBillService;

    @Autowired
    private UserExtractService userExtractService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private SystemUserLevelService systemUserLevelService;

    @Autowired
    private SystemGroupDataService systemGroupDataService;

    @Autowired
    private StoreOrderService storeOrderService;

    @Autowired
    private UserRechargeService userRechargeService;

    @Autowired
    private UserTokenService userTokenService;

    @Autowired
    private UserBrokerageRecordService userBrokerageRecordService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WeChatPayService weChatPayService;

    @Autowired
    private StoreCouponService storeCouponService;

    @Autowired
    private StoreCouponUserService storeCouponUserService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private LoginService loginService;

    @Autowired
    private UserIntegralRecordService userIntegralRecordService;

    @Autowired
    private WechatNewService wechatNewService;

    @Autowired
    private UserExperienceRecordService experienceRecordService;

    @Autowired
    private FrontTokenComponent tokenComponent;


    /**
     * ??????????????????(??????????????? ?????????????????? ????????????)
     */
    @Override
    public UserCommissionResponse getCommission() {
        User user = userService.getInfoException();
        // ???????????????
        BigDecimal yesterdayIncomes = userBrokerageRecordService.getYesterdayIncomes(user.getUid());
        //?????????????????????
        BigDecimal totalMoney = userExtractService.getExtractTotalMoney(user.getUid());

        UserCommissionResponse userCommissionResponse = new UserCommissionResponse();
        userCommissionResponse.setLastDayCount(yesterdayIncomes);
        userCommissionResponse.setExtractCount(totalMoney);
        userCommissionResponse.setCommissionCount(user.getBrokeragePrice());
        return userCommissionResponse;
    }

    /**
     * ????????????/????????????
     *
     * @return BigDecimal
     */
    @Override
    public BigDecimal getSpreadCountByType(Integer type) {
        //????????????/????????????
        Integer userId = userService.getUserIdException();
        if (type == 3) {
            BigDecimal count = userBillService.getSumBigDecimal(null, userId, Constants.USER_BILL_CATEGORY_MONEY, null, Constants.USER_BILL_TYPE_BROKERAGE);
            BigDecimal withdraw = userBillService.getSumBigDecimal(1, userId, Constants.USER_BILL_CATEGORY_MONEY, null, Constants.USER_BILL_TYPE_BROKERAGE); //??????
            return count.subtract(withdraw);
        }

        //????????????
        if (type == 4) {
            return userExtractService.getWithdrawn(null, null);
        }

        return BigDecimal.ZERO;
    }

    /**
     * ????????????
     *
     * @return Boolean
     */
    @Override
    public Boolean extractCash(UserExtractRequest request) {
        switch (request.getExtractType()) {
            case "weixin":
                if (StringUtils.isBlank(request.getWechat())) {
                    throw new CrmebException("?????????????????????");
                }
                request.setAlipayCode(null);
                request.setBankCode(null);
                request.setBankName(null);
                break;
            case "alipay":
                if (StringUtils.isBlank(request.getAlipayCode())) {
                    throw new CrmebException("???????????????????????????");
                }
                request.setWechat(null);
                request.setBankCode(null);
                request.setBankName(null);
                break;
            case "bank":
                if (StringUtils.isBlank(request.getBankName())) {
                    throw new CrmebException("????????????????????????");
                }
                if (StringUtils.isBlank(request.getBankCode())) {
                    throw new CrmebException("????????????????????????");
                }
                request.setWechat(null);
                request.setAlipayCode(null);
                break;
            default:
                throw new CrmebException("?????????????????????");
        }
        return userExtractService.extractApply(request);
    }

    /**
     * ????????????/??????????????????
     *
     * @return UserExtractCashResponse
     */
    @Override
    public List<String> getExtractBank() {
        // ??????????????????
        String bank = systemConfigService.getValueByKeyException(Constants.CONFIG_BANK_LIST).replace("\r\n", "\n");
        List<String> bankArr = new ArrayList<>();
        if (bank.indexOf("\n") > 0) {
            bankArr.addAll(Arrays.asList(bank.split("\n")));
        } else {
            bankArr.add(bank);
        }
        return bankArr;
    }

    /**
     * ??????????????????
     *
     * @return List<UserLevel>
     */
    @Override
    public List<SystemUserLevel> getUserLevelList() {
        return systemUserLevelService.getH5LevelList();
    }

    /**
     * ??????????????? ??????????????????????????????
     *
     * @return List<UserSpreadPeopleItemResponse>
     */
    @Override
    public List<UserSpreadPeopleItemResponse> getSpreadPeopleList(UserSpreadPeopleRequest request, PageParamRequest pageParamRequest) {
        //??????????????????????????????????????????
        Integer userId = userService.getUserIdException();
        List<Integer> userIdList = new ArrayList<>();
        userIdList.add(userId);
        userIdList = userService.getSpreadPeopleIdList(userIdList); //????????????????????????id??????

        if (CollUtil.isEmpty(userIdList)) {//??????????????????????????????????????????
            return new ArrayList<>();
        }
        if (request.getGrade().equals(1)) {// ???????????????
            //?????????????????????
            List<Integer> secondSpreadIdList = userService.getSpreadPeopleIdList(userIdList);
            //???????????????
            userIdList.clear();
            userIdList.addAll(secondSpreadIdList);
        }
        List<UserSpreadPeopleItemResponse> spreadPeopleList = userService.getSpreadPeopleList(userIdList, request.getKeyword(), request.getSortKey(), request.getIsAsc(), pageParamRequest);
        spreadPeopleList.forEach(e -> {
            OrderBrokerageData brokerageData = storeOrderService.getBrokerageData(e.getUid(), userId);
            e.setOrderCount(brokerageData.getNum());
            e.setNumberCount(brokerageData.getPrice());
        });
        return spreadPeopleList;
    }

    /**
     * ??????????????????
     *
     * @return UserRechargeResponse
     */
    @Override
    public UserRechargeFrontResponse getRechargeConfig() {
        UserRechargeFrontResponse userRechargeResponse = new UserRechargeFrontResponse();
        userRechargeResponse.setRechargeQuota(systemGroupDataService.getListByGid(SysGroupDataConstants.GROUP_DATA_ID_RECHARGE_LIST, UserRechargeItemResponse.class));
        String rechargeAttention = systemConfigService.getValueByKey(Constants.CONFIG_RECHARGE_ATTENTION);
        List<String> rechargeAttentionList = new ArrayList<>();
        if (StringUtils.isNotBlank(rechargeAttention)) {
            rechargeAttentionList = CrmebUtil.stringToArrayStrRegex(rechargeAttention, "\n");
        }
        userRechargeResponse.setRechargeAttention(rechargeAttentionList);
        return userRechargeResponse;
    }

    /**
     * ??????????????????
     *
     * @return UserBalanceResponse
     */
    @Override
    public UserBalanceResponse getUserBalance() {
        User info = userService.getInfo();
        BigDecimal recharge = userBillService.getSumBigDecimal(1, info.getUid(), Constants.USER_BILL_CATEGORY_MONEY, null, null);
        BigDecimal orderStatusSum = userBillService.getSumBigDecimal(0, info.getUid(), Constants.USER_BILL_CATEGORY_MONEY, null, null);
//        BigDecimal orderStatusSum = storeOrderService.getSumBigDecimal(info.getUid(), null);
        return new UserBalanceResponse(info.getNowMoney(), recharge, orderStatusSum);
    }

    /**
     * ????????????
     *
     * @return UserSpreadOrderResponse;
     */
    @Override
    public UserSpreadOrderResponse getSpreadOrder(PageParamRequest pageParamRequest) {
        User user = userService.getInfo();
        if (ObjectUtil.isNull(user)) {
            throw new CrmebException("??????????????????");
        }
        UserSpreadOrderResponse spreadOrderResponse = new UserSpreadOrderResponse();
        // ????????????????????????
        Integer spreadCount = userBrokerageRecordService.getSpreadCountByUid(user.getUid());
        spreadOrderResponse.setCount(spreadCount.longValue());
        if (spreadCount.equals(0)) {
            return spreadOrderResponse;
        }

        // ?????????????????????????????????
        List<UserBrokerageRecord> recordList = userBrokerageRecordService.findSpreadListByUid(user.getUid(), pageParamRequest);
        // ???????????????????????????
        List<String> orderNoList = recordList.stream().map(UserBrokerageRecord::getLinkId).collect(Collectors.toList());
        Map<String, StoreOrder> orderMap = storeOrderService.getMapInOrderNo(orderNoList);
        // ???????????????????????????
        List<StoreOrder> storeOrderList = new ArrayList<>(orderMap.values());
        List<Integer> uidList = storeOrderList.stream().map(StoreOrder::getUid).distinct().collect(Collectors.toList());
        HashMap<Integer, User> userMap = userService.getMapListInUid(uidList);

        List<UserSpreadOrderItemResponse> userSpreadOrderItemResponseList = new ArrayList<>();
        List<String> monthList = CollUtil.newArrayList();
        recordList.forEach(record -> {
            UserSpreadOrderItemChildResponse userSpreadOrderItemChildResponse = new UserSpreadOrderItemChildResponse();
            userSpreadOrderItemChildResponse.setOrderId(record.getLinkId());
            userSpreadOrderItemChildResponse.setTime(record.getUpdateTime());
            userSpreadOrderItemChildResponse.setNumber(record.getPrice());
            Integer orderUid = orderMap.get(record.getLinkId()).getUid();
            userSpreadOrderItemChildResponse.setAvatar(userMap.get(orderUid).getAvatar());
            userSpreadOrderItemChildResponse.setNickname(userMap.get(orderUid).getNickname());
            userSpreadOrderItemChildResponse.setType("??????");

            String month = DateUtil.dateToStr(record.getUpdateTime(), Constants.DATE_FORMAT_MONTH);
            if (monthList.contains(month)) {
                //????????????????????????????????????????????????????????????
                for (UserSpreadOrderItemResponse userSpreadOrderItemResponse : userSpreadOrderItemResponseList) {
                    if (userSpreadOrderItemResponse.getTime().equals(month)) {
                        userSpreadOrderItemResponse.getChild().add(userSpreadOrderItemChildResponse);
                        break;
                    }
                }
            } else {// ??????????????????
                //????????????
                UserSpreadOrderItemResponse userSpreadOrderItemResponse = new UserSpreadOrderItemResponse();
                userSpreadOrderItemResponse.setTime(month);
                userSpreadOrderItemResponse.getChild().add(userSpreadOrderItemChildResponse);
                userSpreadOrderItemResponseList.add(userSpreadOrderItemResponse);
                monthList.add(month);
            }
        });

        // ????????????????????????
        Map<String, Integer> countMap = userBrokerageRecordService.getSpreadCountByUidAndMonth(user.getUid(), monthList);
        for (UserSpreadOrderItemResponse userSpreadOrderItemResponse : userSpreadOrderItemResponseList) {
            userSpreadOrderItemResponse.setCount(countMap.get(userSpreadOrderItemResponse.getTime()));
        }

        spreadOrderResponse.setList(userSpreadOrderItemResponseList);
        return spreadOrderResponse;
    }

    /**
     * ??????
     *
     * @return UserSpreadOrderResponse;
     */
    @Override
    @Transactional(rollbackFor = {RuntimeException.class, Error.class, CrmebException.class})
    public OrderPayResultResponse recharge(UserRechargeRequest request) {
        request.setPayType(Constants.PAY_TYPE_WE_CHAT);

        //?????????????????????????????????
        String rechargeMinAmountStr = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_RECHARGE_MIN_AMOUNT);
        BigDecimal rechargeMinAmount = new BigDecimal(rechargeMinAmountStr);
        int compareResult = rechargeMinAmount.compareTo(request.getPrice());
        if (compareResult > 0) {
            throw new CrmebException("????????????????????????" + rechargeMinAmountStr);
        }

        request.setGivePrice(BigDecimal.ZERO);

        if (request.getGroupDataId() > 0) {
            SystemGroupDataRechargeConfigVo systemGroupData = systemGroupDataService.getNormalInfo(request.getGroupDataId(), SystemGroupDataRechargeConfigVo.class);
            if (ObjectUtil.isNull(systemGroupData)) {
                throw new CrmebException("?????????????????????????????????");
            }
            //???????????????
            request.setPrice(systemGroupData.getPrice());
            request.setGivePrice(systemGroupData.getGiveMoney());
        }
        User currentUser = userService.getInfoException();
        //??????????????????
        UserRecharge userRecharge = new UserRecharge();
        userRecharge.setUid(currentUser.getUid());
        userRecharge.setOrderId(CrmebUtil.getOrderNo("recharge"));
        userRecharge.setPrice(request.getPrice());
        userRecharge.setGivePrice(request.getGivePrice());
        userRecharge.setRechargeType(request.getFromType());
        boolean save = userRechargeService.save(userRecharge);
        if (!save) {
            throw new CrmebException("????????????????????????!");
        }

        OrderPayResultResponse response = new OrderPayResultResponse();
        MyRecord record = new MyRecord();
        Map<String, String> unifiedorder = weChatPayService.unifiedRecharge(userRecharge, request.getClientIp());
        record.set("status", true);
        response.setStatus(true);

//        WxPayJsResultVo vo = new WxPayJsResultVo();
//        vo.setAppId(unifiedorder.get("appId"));
//        vo.setNonceStr(unifiedorder.get("nonceStr"));
//        vo.setPackages(unifiedorder.get("package"));
//        vo.setSignType(unifiedorder.get("signType"));
//        vo.setTimeStamp(unifiedorder.get("timeStamp"));
//        vo.setPaySign(unifiedorder.get("paySign"));
//        if (userRecharge.getRechargeType().equals(PayConstants.PAY_CHANNEL_WE_CHAT_H5)) {
//            vo.setMwebUrl(unifiedorder.get("mweb_url"));
//            response.setPayType(PayConstants.PAY_CHANNEL_WE_CHAT_H5);
//        }

        AlipayTradeWapPayRequest req = new AlipayTradeWapPayRequest();
        req.setNotifyUrl(unifiedorder.get("notify-url"));
        req.setReturnUrl(unifiedorder.get("return-url"));

        JSONObject bizContent = new JSONObject();

        response.setOrderNo(userRecharge.getOrderId());
        bizContent.put("out_trade_no", unifiedorder.get("out_trade_no"));
        bizContent.put("total_amount", unifiedorder.get("total_amount"));
        bizContent.put("subject", unifiedorder.get("subject"));
        bizContent.put("product_code", unifiedorder.get("product_code"));
        req.setBizContent(bizContent.toString());

        AlipayTradeWapPayResponse alipayTradeWapPayResponse = null;
        try {
            String appId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_APP_ID);
            String sellerId = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_SELLER_ID);
            String gatewayUrl = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_GATEWAY_URL);
            String merchantPrivateKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_MERCHANT_PRIVATE_KEY);
            String publicKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_PUBLIC_KEY);
            String contentKey = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_CONTENT_KEY);
            String returnUrl = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_RETURN_URL);
            String notifyUrl = systemConfigService.getValueByKeyException(Constants.CONFIG_KEY_PAY_ALIPAY_NOTIFY_URL);

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

            alipayTradeWapPayResponse = alipayClient.pageExecute(req);

            if (alipayTradeWapPayResponse.isSuccess()) {
//                log.info("??????????????????????????? ===> " + response.getBody());
                response.setAlipayRequest(alipayTradeWapPayResponse.getBody());
            } else {
//                log.info("???????????????????????? ===> " + response.getCode() + ", ???????????? ===> " + response.getMsg());
                throw new CrmebException("????????????????????????");
            }
        } catch (AlipayApiException e) {
            throw new CrmebException("????????????????????????");
        }

        return response;
    }

    /**
     * ????????????
     *
     * @return LoginResponse;
     */
    @Override
    public LoginResponse weChatAuthorizeLogin(String code, Integer spreadUid) {
        // ??????code?????????????????????????????????
        WeChatOauthToken oauthToken = wechatNewService.getOauth2AccessToken(code);
        //??????????????????
        UserToken userToken = userTokenService.getByOpenidAndType(oauthToken.getOpenId(), Constants.THIRD_LOGIN_TOKEN_TYPE_PUBLIC);
        LoginResponse loginResponse = new LoginResponse();
        if (ObjectUtil.isNotNull(userToken)) {// ????????????????????????
            User user = userService.getById(userToken.getUid());
            if (!user.getStatus()) {
                throw new CrmebException("?????????????????????????????????????????????");
            }

            // ??????????????????????????????
            user.setLastLoginTime(DateUtil.nowDateTime());
            Boolean execute = transactionTemplate.execute(e -> {
                // ????????????
                if (userService.checkBingSpread(user, spreadUid, "old")) {
                    user.setSpreadUid(spreadUid);
                    user.setSpreadTime(DateUtil.nowDateTime());
                    // ???????????????????????????
                    userService.updateSpreadCountByUid(spreadUid, "add");
                }
                userService.updateById(user);
                return Boolean.TRUE;
            });
            if (!execute) {
                logger.error(StrUtil.format("??????????????????????????????????????????uid={},spreadUid={}", user.getUid(), spreadUid));
            }
            try {
                String token = tokenComponent.createToken(user);
                loginResponse.setToken(token);
            } catch (Exception e) {
                logger.error(StrUtil.format("?????????????????????token?????????uid={}", user.getUid()));
                e.printStackTrace();
            }
            loginResponse.setType("login");
            loginResponse.setUid(user.getUid());
            loginResponse.setNikeName(user.getNickname());
            loginResponse.setPhone(user.getPhone());
            return loginResponse;
        }
        // ????????????????????????????????????
        // ????????????????????????????????????Redis?????????key??????????????????????????????????????????????????????????????????
        WeChatAuthorizeLoginUserInfoVo userInfo = wechatNewService.getSnsUserInfo(oauthToken.getAccessToken(), oauthToken.getOpenId());
        RegisterThirdUserRequest registerThirdUserRequest = new RegisterThirdUserRequest();
        BeanUtils.copyProperties(userInfo, registerThirdUserRequest);
        registerThirdUserRequest.setSpreadPid(spreadUid);
        registerThirdUserRequest.setType(Constants.USER_LOGIN_TYPE_PUBLIC);
        registerThirdUserRequest.setOpenId(oauthToken.getOpenId());
        String key = SecureUtil.md5(oauthToken.getOpenId());
        redisUtil.set(key, JSONObject.toJSONString(registerThirdUserRequest), (long) (60 * 2), TimeUnit.MINUTES);

        loginResponse.setType("register");
        loginResponse.setKey(key);
        return loginResponse;
    }

    /**
     * ??????????????????logo
     *
     * @return String;
     */
    @Override
    public String getLogo() {
        return systemConfigService.getValueByKey(Constants.CONFIG_KEY_MOBILE_LOGIN_LOGO);
    }

    /**
     * ???????????????
     *
     * @param code    String ??????????????????code
     * @param request RegisterThirdUserRequest ????????????
     * @return LoginResponse
     */
    @Override
    public LoginResponse weChatAuthorizeProgramLogin(String code, RegisterThirdUserRequest request) {
        WeChatMiniAuthorizeVo response = wechatNewService.miniAuthCode(code);
        System.out.println("????????????????????? = " + JSON.toJSONString(response));

        //??????????????????
        UserToken userToken = userTokenService.getByOpenidAndType(response.getOpenId(), Constants.THIRD_LOGIN_TOKEN_TYPE_PROGRAM);
        LoginResponse loginResponse = new LoginResponse();
        if (ObjectUtil.isNotNull(userToken)) {// ????????????????????????
            User user = userService.getById(userToken.getUid());
            if (!user.getStatus()) {
                throw new CrmebException("?????????????????????????????????????????????");
            }
            // ??????????????????????????????
            user.setLastLoginTime(DateUtil.nowDateTime());
            Boolean execute = transactionTemplate.execute(e -> {
                // ????????????
                if (userService.checkBingSpread(user, request.getSpreadPid(), "old")) {
                    user.setSpreadUid(request.getSpreadPid());
                    user.setSpreadTime(DateUtil.nowDateTime());
                    // ???????????????????????????
                    userService.updateSpreadCountByUid(request.getSpreadPid(), "add");
                }
                userService.updateById(user);
                return Boolean.TRUE;
            });
            if (!execute) {
                logger.error(StrUtil.format("??????????????????????????????????????????uid={},spreadUid={}", user.getUid(), request.getSpreadPid()));
            }

            try {
                String token = tokenComponent.createToken(user);
                loginResponse.setToken(token);
            } catch (Exception e) {
                logger.error(StrUtil.format("?????????????????????token?????????uid={}", user.getUid()));
                e.printStackTrace();
            }
            loginResponse.setType("login");
            loginResponse.setUid(user.getUid());
            loginResponse.setNikeName(user.getNickname());
            loginResponse.setPhone(user.getPhone());
            return loginResponse;
        }

        if (StrUtil.isBlank(request.getNickName()) && StrUtil.isBlank(request.getAvatar()) && StrUtil.isBlank(request.getHeadimgurl())) {
            // ???????????????????????????????????????
            loginResponse.setType("start");
            return loginResponse;
        }

        request.setType(Constants.USER_LOGIN_TYPE_PROGRAM);
        request.setOpenId(response.getOpenId());
        String key = SecureUtil.md5(response.getOpenId());
        redisUtil.set(key, JSONObject.toJSONString(request), (long) (60 * 2), TimeUnit.MINUTES);
        loginResponse.setType("register");
        loginResponse.setKey(key);
        return loginResponse;
    }

    /**
     * ??????????????????
     *
     * @param type             String ????????????(week-??????month-???)
     * @param pageParamRequest PageParamRequest ??????
     * @return List<LoginResponse>
     */
    @Override
    public List<User> getTopSpreadPeopleListByDate(String type, PageParamRequest pageParamRequest) {
        return userService.getTopSpreadPeopleListByDate(type, pageParamRequest);
    }

    /**
     * ???????????????
     *
     * @param type             String ????????????
     * @param pageParamRequest PageParamRequest ??????
     * @return List<User>
     */
    @Override
    public List<User> getTopBrokerageListByDate(String type, PageParamRequest pageParamRequest) {
        // ????????????????????????????????????
        List<UserBrokerageRecord> recordList = userBrokerageRecordService.getBrokerageTopByDate(type);
        if (CollUtil.isEmpty(recordList)) {
            return null;
        }
        // ??????0???????????????
        for (int i = 0; i < recordList.size(); ) {
            UserBrokerageRecord userBrokerageRecord = recordList.get(i);
            if (userBrokerageRecord.getPrice().compareTo(BigDecimal.ZERO) < 1) {
                recordList.remove(i);
                continue;
            }
            i++;
        }
        if (CollUtil.isEmpty(recordList)) {
            return null;
        }

        List<Integer> uidList = recordList.stream().map(UserBrokerageRecord::getUid).collect(Collectors.toList());
        //????????????
        HashMap<Integer, User> userVoList = userService.getMapListInUid(uidList);

        //??????????????????
        List<User> userList = CollUtil.newArrayList();
        for (UserBrokerageRecord record : recordList) {
            User user = new User();
            User userVo = userVoList.get(record.getUid());

            user.setUid(record.getUid());
            user.setAvatar(userVo.getAvatar());
            user.setBrokeragePrice(record.getPrice());
            if (StrUtil.isBlank(userVo.getNickname())) {
                user.setNickname(userVo.getPhone().substring(0, 2) + "****" + userVo.getPhone().substring(7));
            } else {
                user.setNickname(userVo.getNickname());
            }
            userList.add(user);
        }
        return userList;
    }

    /**
     * ???????????????
     *
     * @return List<SystemGroupData>
     */
    @Override
    public List<UserSpreadBannerResponse> getSpreadBannerList() {
        return systemGroupDataService.getListByGid(Constants.GROUP_DATA_ID_SPREAD_BANNER_LIST, UserSpreadBannerResponse.class);
    }

    /**
     * ????????????????????????????????????
     *
     * @param type String ????????????
     * @return ???????????????
     */
    @Override
    public Integer getNumberByTop(String type) {
        int number = 0;
        Integer userId = userService.getUserIdException();
        PageParamRequest pageParamRequest = new PageParamRequest();
        pageParamRequest.setLimit(100);

        List<UserBrokerageRecord> recordList = userBrokerageRecordService.getBrokerageTopByDate(type);
        if (CollUtil.isEmpty(recordList)) {
            return number;
        }

        for (int i = 0; i < recordList.size(); i++) {
            if (recordList.get(i).getUid().equals(userId)) {
                number = i + 1;
                break;
            }
        }
        return number;
    }

    /**
     * ??????????????????
     *
     * @return Boolean
     */
    @Override
    public Boolean transferIn(BigDecimal price) {
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CrmebException("?????????????????????0");
        }
        //?????????????????????
        User user = userService.getInfo();
        if (ObjectUtil.isNull(user)) {
            throw new CrmebException("??????????????????");
        }
        BigDecimal subtract = user.getBrokeragePrice();
        if (subtract.compareTo(price) < 0) {
            throw new CrmebException("??????????????????????????? " + subtract + "???");
        }
        // userBill??????????????????
        UserBill userBill = new UserBill();
        userBill.setUid(user.getUid());
        userBill.setLinkId("0");
        userBill.setPm(1);
        userBill.setTitle("???????????????");
        userBill.setCategory(Constants.USER_BILL_CATEGORY_MONEY);
        userBill.setType(Constants.USER_BILL_TYPE_TRANSFER_IN);
        userBill.setNumber(price);
        userBill.setBalance(user.getNowMoney().add(price));
        userBill.setMark(StrUtil.format("???????????????,??????{}", price));
        userBill.setStatus(1);
        userBill.setCreateTime(DateUtil.nowDateTime());

        // userBrokerage????????????
        UserBrokerageRecord brokerageRecord = new UserBrokerageRecord();
        brokerageRecord.setUid(user.getUid());
        brokerageRecord.setLinkId("0");
        brokerageRecord.setLinkType(BrokerageRecordConstants.BROKERAGE_RECORD_LINK_TYPE_YUE);
        brokerageRecord.setType(BrokerageRecordConstants.BROKERAGE_RECORD_TYPE_SUB);
        brokerageRecord.setTitle(BrokerageRecordConstants.BROKERAGE_RECORD_TITLE_BROKERAGE_YUE);
        brokerageRecord.setPrice(price);
        brokerageRecord.setBalance(user.getNowMoney().add(price));
        brokerageRecord.setMark(StrUtil.format("????????????????????????{}", price));
        brokerageRecord.setStatus(BrokerageRecordConstants.BROKERAGE_RECORD_STATUS_COMPLETE);
        brokerageRecord.setCreateTime(DateUtil.nowDateTime());

        Boolean execute = transactionTemplate.execute(e -> {
            // ?????????
            userService.operationBrokerage(user.getUid(), price, user.getBrokeragePrice(), "sub");
            // ?????????
            userService.operationNowMoney(user.getUid(), price, user.getNowMoney(), "add");
            userBillService.save(userBill);
            userBrokerageRecordService.save(brokerageRecord);
            return Boolean.TRUE;
        });
        return execute;
    }

    /**
     * ????????????
     */
    @Override
    public PageInfo<UserExtractRecordResponse> getExtractRecord(PageParamRequest pageParamRequest) {
        Integer userId = userService.getUserIdException();
        return userExtractService.getExtractRecord(userId, pageParamRequest);
    }

    /**
     * ??????????????????
     *
     * @param pageParamRequest ????????????
     */
    @Override
    public PageInfo<SpreadCommissionDetailResponse> getSpreadCommissionDetail(PageParamRequest pageParamRequest) {
        User user = userService.getInfoException();
        return userBrokerageRecordService.findDetailListByUid(user.getUid(), pageParamRequest);
    }

    /**
     * ??????????????????????????????
     *
     * @param type ???????????????all-?????????expenditure-?????????income-??????
     * @return CommonPage
     */
    @Override
    public CommonPage<UserRechargeBillRecordResponse> nowMoneyBillRecord(String type, PageParamRequest pageRequest) {
        User user = userService.getInfo();
        if (ObjectUtil.isNull(user)) {
            throw new CrmebException("??????????????????");
        }
        PageInfo<UserBill> billPageInfo = userBillService.nowMoneyBillRecord(user.getUid(), type, pageRequest);
        List<UserBill> list = billPageInfo.getList();

        // ?????????-???
        Map<String, List<UserBill>> map = CollUtil.newHashMap();
        list.forEach(i -> {
            String month = StrUtil.subPre(DateUtil.dateToStr(i.getCreateTime(), Constants.DATE_FORMAT), 7);
            if (map.containsKey(month)) {
                map.get(month).add(i);
            } else {
                List<UserBill> billList = CollUtil.newArrayList();
                billList.add(i);
                map.put(month, billList);
            }
        });
        List<UserRechargeBillRecordResponse> responseList = CollUtil.newArrayList();
        map.forEach((key, value) -> {
            UserRechargeBillRecordResponse response = new UserRechargeBillRecordResponse();
            response.setDate(key);
            response.setList(value);
            responseList.add(response);
        });

        PageInfo<UserRechargeBillRecordResponse> pageInfo = CommonPage.copyPageInfo(billPageInfo, responseList);
        return CommonPage.restPage(pageInfo);
    }

    /**
     * ???????????????????????????
     *
     * @param request ????????????
     * @return ????????????
     */
    @Override
    public LoginResponse registerBindingPhone(WxBindingPhoneRequest request) {
        checkBindingPhone(request);

        // ???????????????????????????????????????
        Object o = redisUtil.get(request.getKey());
        if (ObjectUtil.isNull(o)) {
            throw new CrmebException("???????????????????????????????????????????????????");
        }
        RegisterThirdUserRequest registerThirdUserRequest = JSONObject.parseObject(o.toString(), RegisterThirdUserRequest.class);

        boolean isNew = true;

        User user = userService.getByPhone(request.getPhone());
        if (ObjectUtil.isNull(user)) {
            user = userService.registerByThird(registerThirdUserRequest);
            user.setPhone(request.getPhone());
            user.setAccount(request.getPhone());
            user.setSpreadUid(0);
            user.setPwd(CommonUtil.createPwd(request.getPhone()));
        } else {// ?????????????????????????????????????????????
            // ????????????????????????token
            int type = 0;
            switch (request.getType()) {
                case "public":
                    type = Constants.THIRD_LOGIN_TOKEN_TYPE_PUBLIC;
                    break;
                case "routine":
                    type = Constants.THIRD_LOGIN_TOKEN_TYPE_PROGRAM;
                    break;
            }

            UserToken userToken = userTokenService.getTokenByUserId(user.getUid(), type);
            if (ObjectUtil.isNotNull(userToken)) {
                throw new CrmebException("????????????????????????");
            }
            isNew = false;
        }

        User finalUser = user;
        boolean finalIsNew = isNew;
        Boolean execute = transactionTemplate.execute(e -> {
            if (finalIsNew) {// ?????????
                // ????????????
                if (userService.checkBingSpread(finalUser, registerThirdUserRequest.getSpreadPid(), "new")) {
                    finalUser.setSpreadUid(registerThirdUserRequest.getSpreadPid());
                    finalUser.setSpreadTime(DateUtil.nowDateTime());
                    userService.updateSpreadCountByUid(registerThirdUserRequest.getSpreadPid(), "add");
                }
                userService.save(finalUser);
                // ???????????????
                giveNewPeopleCoupon(finalUser.getUid());
            }
            switch (request.getType()) {
                case "public":
                    userTokenService.bind(registerThirdUserRequest.getOpenId(), Constants.THIRD_LOGIN_TOKEN_TYPE_PUBLIC, finalUser.getUid());
                    break;
                case "routine":
                    userTokenService.bind(registerThirdUserRequest.getOpenId(), Constants.THIRD_LOGIN_TOKEN_TYPE_PROGRAM, finalUser.getUid());
                    break;
            }
            return Boolean.TRUE;
        });
        if (!execute) {
            logger.error("?????????????????????????????????nickName = " + registerThirdUserRequest.getNickName());
        } else if (!isNew) {// ????????????????????????
            if (ObjectUtil.isNotNull(registerThirdUserRequest.getSpreadPid()) && registerThirdUserRequest.getSpreadPid() > 0) {
                loginService.bindSpread(finalUser, registerThirdUserRequest.getSpreadPid());
            }
        }
        LoginResponse loginResponse = new LoginResponse();
        try {
            String token = tokenComponent.createToken(finalUser);
            loginResponse.setToken(token);
        } catch (Exception e) {
            logger.error(StrUtil.format("????????????????????????????????????token?????????uid={}", finalUser.getUid()));
            e.printStackTrace();
        }
        loginResponse.setType("login");
        loginResponse.setUid(user.getUid());
        loginResponse.setNikeName(user.getNickname());
        loginResponse.setPhone(user.getPhone());
        return loginResponse;
    }

    /**
     * ????????????????????????
     *
     * @param pageParamRequest ????????????
     * @return List<UserIntegralRecord>
     */
    @Override
    public List<UserIntegralRecord> getUserIntegralRecordList(PageParamRequest pageParamRequest) {
        Integer uid = userService.getUserIdException();
        return userIntegralRecordService.findUserIntegralRecordList(uid, pageParamRequest);
    }

    /**
     * ????????????????????????
     *
     * @return IntegralUserResponse
     */
    @Override
    public IntegralUserResponse getIntegralUser() {
        User user = userService.getInfoException();
        IntegralUserResponse userSignInfoResponse = new IntegralUserResponse();

        //??????
        Integer sumIntegral = userIntegralRecordService.getSumIntegral(user.getUid(), IntegralRecordConstants.INTEGRAL_RECORD_TYPE_ADD, "", null);
        Integer deductionIntegral = userIntegralRecordService.getSumIntegral(user.getUid(), IntegralRecordConstants.INTEGRAL_RECORD_TYPE_SUB, "", IntegralRecordConstants.INTEGRAL_RECORD_LINK_TYPE_ORDER);
        userSignInfoResponse.setSumIntegral(sumIntegral);
        userSignInfoResponse.setDeductionIntegral(deductionIntegral);
        // ????????????
        Integer frozenIntegral = userIntegralRecordService.getFrozenIntegralByUid(user.getUid());
        userSignInfoResponse.setFrozenIntegral(frozenIntegral);
        userSignInfoResponse.setIntegral(user.getIntegral());
        return userSignInfoResponse;
    }

    /**
     * ????????????????????????
     *
     * @param pageParamRequest ????????????
     * @return List<UserExperienceRecord>
     */
    @Override
    public List<UserExperienceRecord> getUserExperienceList(PageParamRequest pageParamRequest) {
        Integer userId = userService.getUserIdException();
        return experienceRecordService.getH5List(userId, pageParamRequest);
    }

    /**
     * ??????????????????
     *
     * @return UserExtractCashResponse
     */
    @Override
    public UserExtractCashResponse getExtractUser() {
        User user = userService.getInfoException();
        // ??????????????????
        String minPrice = systemConfigService.getValueByKeyException(SysConfigConstants.CONFIG_EXTRACT_MIN_PRICE);
        // ????????????
        String extractTime = systemConfigService.getValueByKey(SysConfigConstants.CONFIG_EXTRACT_FREEZING_TIME);
        // ???????????????
        BigDecimal brokeragePrice = user.getBrokeragePrice();
        // ????????????
        BigDecimal freeze = userBrokerageRecordService.getFreezePrice(user.getUid());
        return new UserExtractCashResponse(minPrice, brokeragePrice, freeze, extractTime);
    }

    /**
     * ?????????????????????
     *
     * @return UserSpreadPeopleResponse
     */
    @Override
    public UserSpreadPeopleResponse getSpreadPeopleCount() {
        //??????????????????????????????????????????
        UserSpreadPeopleResponse userSpreadPeopleResponse = new UserSpreadPeopleResponse();
        List<Integer> userIdList = new ArrayList<>();
        Integer userId = userService.getUserIdException();
        userIdList.add(userId);
        userIdList = userService.getSpreadPeopleIdList(userIdList); //????????????????????????id??????

        if (CollUtil.isEmpty(userIdList)) {//??????????????????????????????????????????
            userSpreadPeopleResponse.setCount(0);
            userSpreadPeopleResponse.setTotal(0);
            userSpreadPeopleResponse.setTotalLevel(0);
            return userSpreadPeopleResponse;
        }

        userSpreadPeopleResponse.setTotal(userIdList.size()); //???????????????
        //?????????????????????
        List<Integer> secondSpreadIdList = userService.getSpreadPeopleIdList(userIdList);
        if (CollUtil.isEmpty(secondSpreadIdList)) {
            userSpreadPeopleResponse.setTotalLevel(0);
            userSpreadPeopleResponse.setCount(userSpreadPeopleResponse.getTotal());
            return userSpreadPeopleResponse;
        }
        userSpreadPeopleResponse.setTotalLevel(secondSpreadIdList.size());
        userSpreadPeopleResponse.setCount(userIdList.size() + secondSpreadIdList.size());
        return userSpreadPeopleResponse;
    }

    /**
     * ???????????????????????????
     */
    private void checkBindingPhone(WxBindingPhoneRequest request) {
        if (!request.getType().equals("public") && !request.getType().equals("routine") && !request.getType().equals("iosWx") && !request.getType().equals("androidWx")) {
            throw new CrmebException("?????????????????????");
        }
        if (request.getType().equals("public")) {
            if (StrUtil.isBlank(request.getCaptcha())) {
                throw new CrmebException("?????????????????????");
            }
            boolean matchPhone = ReUtil.isMatch(RegularConstants.PHONE_TWO, request.getPhone());
            if (!matchPhone) {
                throw new CrmebException("???????????????????????????????????????????????????");
            }
            // ??????????????????????????????
            boolean match = ReUtil.isMatch(RegularConstants.VALIDATE_CODE_NUM_SIX, request.getCaptcha());
            if (!match) {
                throw new CrmebException("??????????????????????????????????????????6?????????");
            }
            checkValidateCode(request.getPhone(), request.getCaptcha());
        } else {
            // ????????????
            if (StrUtil.isBlank(request.getCode())) {
                throw new CrmebException("????????????????????????code????????????");
            }
            if (StrUtil.isBlank(request.getEncryptedData())) {
                throw new CrmebException("????????????????????????????????????????????????");
            }
            if (StrUtil.isBlank(request.getIv())) {
                throw new CrmebException("???????????????????????????????????????????????????????????????");
            }
            // ??????appid
            String programAppId = systemConfigService.getValueByKey(WeChatConstants.WECHAT_MINI_APPID);
            if (StringUtils.isBlank(programAppId)) {
                throw new CrmebException("???????????????appId?????????");
            }

            WeChatMiniAuthorizeVo response = wechatNewService.miniAuthCode(request.getCode());
//            WeChatMiniAuthorizeVo response = weChatService.programAuthorizeLogin(request.getCode());
            System.out.println("????????????????????? = " + JSON.toJSONString(response));
            String decrypt = WxUtil.decrypt(programAppId, request.getEncryptedData(), response.getSessionKey(), request.getIv());
            if (StrUtil.isBlank(decrypt)) {
                throw new CrmebException("??????????????????????????????????????????");
            }
            JSONObject jsonObject = JSONObject.parseObject(decrypt);
            if (StrUtil.isBlank(jsonObject.getString("phoneNumber"))) {
                throw new CrmebException("??????????????????????????????????????????????????????");
            }
            request.setPhone(jsonObject.getString("phoneNumber"));
        }
    }

    /**
     * ???????????????
     *
     * @param uid ??????uid
     */
    private void giveNewPeopleCoupon(Integer uid) {
        // ??????????????????????????????????????????
        List<StoreCouponUser> couponUserList = CollUtil.newArrayList();
        List<StoreCoupon> couponList = storeCouponService.findRegisterList();
        if (CollUtil.isNotEmpty(couponList)) {
            couponList.forEach(storeCoupon -> {
                //??????????????????????????????
                if (!storeCoupon.getIsFixedTime()) {
                    String endTime = DateUtil.addDay(DateUtil.nowDate(Constants.DATE_FORMAT), storeCoupon.getDay(), Constants.DATE_FORMAT);
                    storeCoupon.setUseEndTime(DateUtil.strToDate(endTime, Constants.DATE_FORMAT));
                    storeCoupon.setUseStartTime(DateUtil.nowDateTimeReturnDate(Constants.DATE_FORMAT));
                }

                StoreCouponUser storeCouponUser = new StoreCouponUser();
                storeCouponUser.setCouponId(storeCoupon.getId());
                storeCouponUser.setName(storeCoupon.getName());
                storeCouponUser.setMoney(storeCoupon.getMoney());
                storeCouponUser.setMinPrice(storeCoupon.getMinPrice());
                storeCouponUser.setUseType(storeCoupon.getUseType());
                if (storeCoupon.getIsFixedTime()) {// ??????????????????
                    storeCouponUser.setStartTime(storeCoupon.getUseStartTime());
                    storeCouponUser.setEndTime(storeCoupon.getUseEndTime());
                } else {// ????????????????????????
                    Date nowDate = DateUtil.nowDateTime();
                    storeCouponUser.setStartTime(nowDate);
                    DateTime dateTime = cn.hutool.core.date.DateUtil.offsetDay(nowDate, storeCoupon.getDay());
                    storeCouponUser.setEndTime(dateTime);
                }
                storeCouponUser.setType("register");
                if (storeCoupon.getUseType() > 1) {
                    storeCouponUser.setPrimaryKey(storeCoupon.getPrimaryKey());
                }
                storeCouponUser.setType(CouponConstants.STORE_COUPON_USER_TYPE_REGISTER);
                couponUserList.add(storeCouponUser);
            });
        }

        // ?????????????????????
        if (CollUtil.isNotEmpty(couponUserList)) {
            couponUserList.forEach(couponUser -> couponUser.setUid(uid));
            storeCouponUserService.saveBatch(couponUserList);
            couponList.forEach(coupon -> storeCouponService.deduction(coupon.getId(), 1, coupon.getIsLimited()));
        }
    }

    /**
     * ?????????????????????
     *
     * @param phone ?????????
     * @param code  ?????????
     */
    private void checkValidateCode(String phone, String code) {
        Object validateCode = redisUtil.get(SmsConstants.SMS_VALIDATE_PHONE + phone);
        if (validateCode == null) {
            throw new CrmebException("??????????????????");
        }
        if (!validateCode.toString().equals(code)) {
            throw new CrmebException("???????????????");
        }
        //???????????????
        redisUtil.delete(SmsConstants.SMS_VALIDATE_PHONE + phone);
    }
}
