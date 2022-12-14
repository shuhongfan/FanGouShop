package com.shf.service.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shf.common.page.CommonPage;
import com.shf.common.request.UserCouponReceiveRequest;
import com.shf.common.vo.MyRecord;
import com.shf.common.request.PageParamRequest;
import com.shf.common.constants.Constants;
import com.shf.common.constants.CouponConstants;
import com.shf.common.exception.CrmebException;
import com.shf.common.vo.OrderInfoDetailVo;
import com.shf.common.vo.OrderInfoVo;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.shf.common.utils.CrmebUtil;
import com.shf.common.utils.DateUtil;
import com.shf.common.utils.RedisUtil;
import com.shf.common.model.coupon.StoreCoupon;
import com.shf.common.model.coupon.StoreCouponUser;
import com.shf.common.request.StoreCouponUserRequest;
import com.shf.common.request.StoreCouponUserSearchRequest;
import com.shf.common.response.StoreCouponUserOrder;
import com.shf.common.response.StoreCouponUserResponse;
import com.shf.common.model.user.User;
import com.shf.service.dao.StoreCouponUserDao;
import com.shf.service.service.StoreCouponUserService;
import com.shf.service.service.StoreCouponService;
import com.shf.service.service.StoreProductService;
import com.shf.service.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * StoreCouponUserService ?????????
 */
@Service
public class StoreCouponUserServiceImpl extends ServiceImpl<StoreCouponUserDao, StoreCouponUser> implements StoreCouponUserService {

    @Resource
    private StoreCouponUserDao dao;

    @Autowired
    private StoreCouponService storeCouponService;

    @Autowired
    private UserService userService;

    @Autowired
    private StoreProductService storeProductService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RedisUtil redisUtil;

    /**
    * ??????
    * @param request ????????????
    * @param pageParamRequest ???????????????
    * @return List<StoreCouponUser>
    */
    @Override
    public PageInfo<StoreCouponUserResponse> getList(StoreCouponUserSearchRequest request, PageParamRequest pageParamRequest) {
        Page<StoreCouponUser> storeCouponUserPage = PageHelper.startPage(pageParamRequest.getPage(), pageParamRequest.getLimit());

        //??? StoreCouponUser ?????????????????????
        LambdaQueryWrapper<StoreCouponUser> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        if (!StringUtils.isBlank(request.getName())) {
            lambdaQueryWrapper.like(StoreCouponUser::getName, request.getName());
        }

        if (request.getUid() !=null && request.getUid() > 0) {
            lambdaQueryWrapper.eq(StoreCouponUser::getUid, request.getUid());
        }

        if (request.getStatus() !=null) {
            lambdaQueryWrapper.eq(StoreCouponUser::getStatus, request.getStatus());
        }

        if (request.getCouponId() != null) {
            lambdaQueryWrapper.eq(StoreCouponUser::getCouponId, request.getCouponId());
        }
        lambdaQueryWrapper.orderByDesc(StoreCouponUser::getId);
        List<StoreCouponUser> storeCouponUserList = dao.selectList(lambdaQueryWrapper);
        if (storeCouponUserList.size() < 1) {
            return new PageInfo<>();
        }
        ArrayList<StoreCouponUserResponse> storeCouponUserResponseList = new ArrayList<>();

        List<Integer> uidList = storeCouponUserList.stream().map(StoreCouponUser::getUid).distinct().collect(Collectors.toList());
        HashMap<Integer, User> userList = userService.getMapListInUid(uidList);

        for (StoreCouponUser storeCouponUser : storeCouponUserList) {
            StoreCouponUserResponse storeCouponUserResponse = new StoreCouponUserResponse();
            BeanUtils.copyProperties(storeCouponUser, storeCouponUserResponse);
            if (userList.containsKey(storeCouponUser.getUid())) {
                storeCouponUserResponse.setNickname(userList.get(storeCouponUser.getUid()).getNickname());
                storeCouponUserResponse.setAvatar(userList.get(storeCouponUser.getUid()).getAvatar());
            }
            storeCouponUserResponseList.add(storeCouponUserResponse);
        }
        return CommonPage.copyPageInfo(storeCouponUserPage, storeCouponUserResponseList);
    }

    /**
     * ??????/????????????
     * @param request ????????????
     * @return boolean
     */
    @Override
    public Boolean receive(StoreCouponUserRequest request) {
        //?????????????????????
        StoreCoupon storeCoupon = storeCouponService.getInfoException(request.getCouponId());

        List<Integer> uidList = CrmebUtil.stringToArray(request.getUid());
        if (uidList.size() < 1) {
            throw new CrmebException("??????????????????");
        }

        //????????????????????????
        if (storeCoupon.getIsLimited()) {
            //????????????????????????????????????
            if (storeCoupon.getLastTotal() < uidList.size()) {
                throw new CrmebException("?????????????????????????????????");
            }
        }

        //?????????????????????????????????
        filterReceiveUserInUid(storeCoupon.getId(), uidList);
        if (uidList.size() < 1) {
            //?????????????????????
            throw new CrmebException("?????????????????????????????????????????????");
        }

        //??????????????????????????????
        if (!storeCoupon.getIsFixedTime()) {
            String endTime = DateUtil.addDay(DateUtil.nowDate(Constants.DATE_FORMAT), storeCoupon.getDay(), Constants.DATE_FORMAT);
            storeCoupon.setUseEndTime(DateUtil.strToDate(endTime, Constants.DATE_FORMAT));
            storeCoupon.setUseStartTime(DateUtil.nowDateTimeReturnDate(Constants.DATE_FORMAT));
        }

        ArrayList<StoreCouponUser> storeCouponUserList = new ArrayList<>();

        for (Integer uid : uidList) {
            StoreCouponUser storeCouponUser = new StoreCouponUser();
            storeCouponUser.setCouponId(storeCoupon.getId());
            storeCouponUser.setUid(uid);
            storeCouponUser.setName(storeCoupon.getName());
            storeCouponUser.setMoney(storeCoupon.getMoney());
            storeCouponUser.setMinPrice(storeCoupon.getMinPrice());
            storeCouponUser.setStartTime(storeCoupon.getUseStartTime());
            storeCouponUser.setEndTime(storeCoupon.getUseEndTime());
            storeCouponUser.setUseType(storeCoupon.getUseType());
            storeCouponUser.setType(CouponConstants.STORE_COUPON_USER_TYPE_SEND);
            if (storeCoupon.getUseType() > 1) {
                storeCouponUser.setPrimaryKey(storeCoupon.getPrimaryKey());
            }
            storeCouponUserList.add(storeCouponUser);
        }

        storeCoupon.setLastTotal(storeCoupon.getLastTotal() - uidList.size());

        Boolean execute = transactionTemplate.execute(e -> {
            saveBatch(storeCouponUserList);
            storeCouponService.updateById(storeCoupon);
            return Boolean.TRUE;
        });

        return execute;
    }

    /**
     * ??????????????????????????????????????????id
     * @param couponId Integer ?????????id
     * @param uidList List<Integer> ??????id??????
     */
    private void filterReceiveUserInUid(Integer couponId, List<Integer> uidList) {
        LambdaQueryWrapper<StoreCouponUser> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(StoreCouponUser::getCouponId, couponId);
        lambdaQueryWrapper.in(StoreCouponUser::getUid, uidList);
        List<StoreCouponUser> storeCouponUserList = dao.selectList(lambdaQueryWrapper);
        if (storeCouponUserList != null) {
            List<Integer> receiveUidList = storeCouponUserList.stream().map(StoreCouponUser::getUid).distinct().collect(Collectors.toList());
            uidList.removeAll(receiveUidList);
        }
    }

    /**
     * ???????????????????????????
     * @param userId Integer ??????id
     * @author Mr.Zhang
     * @since 2020-05-18
     * @return boolean
     */
    @Override
    public HashMap<Integer, StoreCouponUser>  getMapByUserId(Integer userId) {
        List<StoreCouponUser> list = findListByUid(userId);

        if (list.size() < 1) {
            return null;
        }

        HashMap<Integer, StoreCouponUser> map = new HashMap<>();
        for (StoreCouponUser info : list) {
            map.put(info.getCouponId(), info);
        }
        return map;
    }

    private List<StoreCouponUser> findListByUid(Integer uid) {
        LambdaQueryWrapper<StoreCouponUser> lwq = new LambdaQueryWrapper<>();
        lwq.eq(StoreCouponUser::getUid, uid);
        return dao.selectList(lwq);
    }

    /**
     * ???????????????id?????????????????????
     * @param preOrderNo ??????????????????
     * @return ???????????????
     */
    @Override
    public List<StoreCouponUserOrder> getListByPreOrderNo(String preOrderNo) {
        // ?????????????????????????????????
        String key = "user_order:" + preOrderNo;
        boolean exists = redisUtil.exists(key);
        if (!exists) {
            throw new CrmebException("????????????????????????");
        }
        String orderVoString = redisUtil.get(key).toString();
        OrderInfoVo orderInfoVo = JSONObject.parseObject(orderVoString, OrderInfoVo.class);
        //??????id??????
        List<Integer> productIds = orderInfoVo.getOrderDetailList().stream().map(OrderInfoDetailVo::getProductId).distinct().collect(Collectors.toList());

        //?????????????????????
        BigDecimal maxPrice = orderInfoVo.getProTotalFee();

        LambdaQueryWrapper<StoreCouponUser> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        Date date = DateUtil.nowDateTime();
        lambdaQueryWrapper.eq(StoreCouponUser::getStatus, 0)
                .le(StoreCouponUser::getMinPrice, maxPrice)
                .lt(StoreCouponUser::getStartTime, date)
                .gt(StoreCouponUser::getEndTime, date);
        lambdaQueryWrapper.eq(StoreCouponUser::getUid, userService.getUserIdException());
        getPrimaryKeySql(lambdaQueryWrapper, StringUtils.join(productIds, ","));


        ArrayList<StoreCouponUserOrder> storeCouponUserOrderArrayList = new ArrayList<>();
        List<StoreCouponUser> storeCouponUserList = dao.selectList(lambdaQueryWrapper);
        if (storeCouponUserList.size() < 1) {
            return storeCouponUserOrderArrayList;
        }
        //?????????????????? ????????????
        for (StoreCouponUser storeCouponUser : storeCouponUserList) {
            StoreCouponUserOrder storeCouponUserOrder = new StoreCouponUserOrder();
            BeanUtils.copyProperties(storeCouponUser, storeCouponUserOrder);
            storeCouponUserOrderArrayList.add(storeCouponUserOrder);
        }
        return storeCouponUserOrderArrayList;
    }

    /**
     * ???????????????
     * @param type ?????????usable-?????????unusable-?????????
     * @param userId ??????id
     * @param pageParamRequest ???????????????
     * @return List<StoreCouponUser>
     */
    private List<StoreCouponUser> getH5List(String type, Integer userId, PageParamRequest pageParamRequest) {
        PageHelper.startPage(pageParamRequest.getPage(), pageParamRequest.getLimit());

        //??? StoreCouponUser ?????????????????????
        LambdaQueryWrapper<StoreCouponUser> lqw = new LambdaQueryWrapper<>();

        lqw.eq(StoreCouponUser::getUid, userId);
        if (type.equals("usable")) {
            lqw.eq(StoreCouponUser::getStatus, CouponConstants.STORE_COUPON_USER_STATUS_USABLE);
            lqw.orderByDesc(StoreCouponUser::getId);
        }
        if (type.equals("unusable")) {
            lqw.gt(StoreCouponUser::getStatus, CouponConstants.STORE_COUPON_USER_STATUS_USABLE);
            lqw.last(StrUtil.format(" order by case `status` when {} then {} when {} then {} when {} then {} end", 0, 1, 1, 2, 2, 3));
        }

        List<StoreCouponUser> storeCouponUserList = dao.selectList(lqw);
        if (CollUtil.isEmpty(storeCouponUserList)) {
            return CollUtil.newArrayList();
        }
        return storeCouponUserList;
    }

    /**
     * ???????????????????????????
     */
    @Override
    public void overdueTask() {
        // ??????????????????????????????????????????
        LambdaQueryWrapper<StoreCouponUser> lqw = new LambdaQueryWrapper<>();
        lqw.eq(StoreCouponUser::getStatus, 0);
        List<StoreCouponUser> couponList = dao.selectList(lqw);
        if (CollUtil.isEmpty(couponList)) {
            return;
        }
        // ???????????????????????????
        List<StoreCouponUser> updateList = CollUtil.newArrayList();
        String nowDateStr = DateUtil.nowDate(Constants.DATE_FORMAT);
        couponList.forEach(coupon -> {
            if (ObjectUtil.isNotNull(coupon.getEndTime())) {
                String endDateStr = DateUtil.dateToStr(coupon.getEndTime(), Constants.DATE_FORMAT);
                if (DateUtil.compareDate(nowDateStr, endDateStr, Constants.DATE_FORMAT) >= 0) {
                    coupon.setStatus(2);
                    updateList.add(coupon);
                }
            }
        });

        if (CollUtil.isEmpty(updateList)) {
            return;
        }
        boolean update = updateBatchById(updateList);
        if (!update) throw new CrmebException("???????????????????????????????????????");
    }

    /**
     * ?????????????????????
     */
    @Override
    public Boolean receiveCoupon(UserCouponReceiveRequest request) {
        // ?????????????????????
        StoreCoupon storeCoupon = storeCouponService.getInfoException(request.getCouponId());

        Integer userId = userService.getUserIdException();

        //????????????????????????,????????????????????????
        if (storeCoupon.getIsLimited() && storeCoupon.getLastTotal() < 1) {
            throw new CrmebException("?????????????????????????????????");
        }
        //?????????????????????????????????
        List<Integer> uidList = CollUtil.newArrayList();
        uidList.add(userId);
        filterReceiveUserInUid(storeCoupon.getId(), uidList);
        if (uidList.size() < 1) {
            //?????????????????????
            throw new CrmebException("?????????????????????????????????????????????");
        }
        //??????????????????????????????
        if (!storeCoupon.getIsFixedTime()) {
            String endTime = DateUtil.addDay(DateUtil.nowDate(Constants.DATE_FORMAT), storeCoupon.getDay(), Constants.DATE_FORMAT);
            storeCoupon.setUseEndTime(DateUtil.strToDate(endTime, Constants.DATE_FORMAT));
            storeCoupon.setUseStartTime(DateUtil.nowDateTimeReturnDate(Constants.DATE_FORMAT));
        }
        StoreCouponUser storeCouponUser = new StoreCouponUser();
        storeCouponUser.setCouponId(storeCoupon.getId());
        storeCouponUser.setUid(userId);
        storeCouponUser.setName(storeCoupon.getName());
        storeCouponUser.setMoney(storeCoupon.getMoney());
        storeCouponUser.setMinPrice(storeCoupon.getMinPrice());
        storeCouponUser.setStartTime(storeCoupon.getUseStartTime());
        storeCouponUser.setEndTime(storeCoupon.getUseEndTime());
        storeCouponUser.setUseType(storeCoupon.getUseType());
        storeCouponUser.setType(CouponConstants.STORE_COUPON_USER_TYPE_GET);
        if (storeCoupon.getUseType() > 1) {
            storeCouponUser.setPrimaryKey(storeCoupon.getPrimaryKey());
        }
        Boolean execute = transactionTemplate.execute(e -> {
            save(storeCouponUser);
            storeCouponService.deduction(storeCoupon.getId(), 1, storeCoupon.getIsLimited());
            return Boolean.TRUE;
        });

        return execute;
    }

    /**
     * ????????????????????????
     * @param couponId ???????????????
     * @param uid  ??????uid
     * @return MyRecord
     */
    @Override
    public MyRecord paySuccessGiveAway(Integer couponId, Integer uid) {
        MyRecord record = new MyRecord();
        record.set("status", "fail");
        // ?????????????????????
        StoreCoupon storeCoupon = storeCouponService.getById(couponId);
        if (ObjectUtil.isNull(storeCoupon) || storeCoupon.getIsDel() || !storeCoupon.getStatus()) {
            record.set("errMsg", "??????????????????????????????????????????");
            return record;
        }

        // ?????????????????????????????????
        if (ObjectUtil.isNotNull(storeCoupon.getReceiveStartTime())) {
            //??????????????????
            String date = DateUtil.nowDateTimeStr();
            int result = DateUtil.compareDate(date, DateUtil.dateToStr(storeCoupon.getReceiveStartTime(), Constants.DATE_FORMAT), Constants.DATE_FORMAT);
            if (result == -1) {
                // ?????????
                record.set("errMsg", "????????????????????????????????????");
                return record;
            }
        }

        //???????????????
        if (storeCoupon.getReceiveEndTime() != null) {
            //??????????????????
            String date = DateUtil.nowDateTimeStr();
            int result = DateUtil.compareDate(date, DateUtil.dateToStr(storeCoupon.getReceiveEndTime(), Constants.DATE_FORMAT), Constants.DATE_FORMAT);
            if (result == 1) {
                //?????????
                record.set("errMsg", "???????????????????????????????????????");
                return record;
            }
        }

        //????????????????????????
        if (storeCoupon.getIsLimited() && storeCoupon.getLastTotal() < 1) {
            record.set("errMsg", "?????????????????????????????????");
            return record;
        }

        //?????????????????????????????????
        List<Integer> uidList = CollUtil.newArrayList();
        uidList.add(uid);
        filterReceiveUserInUid(storeCoupon.getId(), uidList);
        if (uidList.size() < 1) {
            //?????????????????????
            record.set("errMsg", "?????????????????????????????????????????????");
            return record;
        }

        //??????????????????????????????
        if (!storeCoupon.getIsFixedTime()) {
            String endTime = DateUtil.addDay(DateUtil.nowDate(Constants.DATE_FORMAT), storeCoupon.getDay(), Constants.DATE_FORMAT);
            storeCoupon.setUseEndTime(DateUtil.strToDate(endTime, Constants.DATE_FORMAT));
            storeCoupon.setUseStartTime(DateUtil.nowDateTimeReturnDate(Constants.DATE_FORMAT));
        }

        StoreCouponUser storeCouponUser = new StoreCouponUser();
        storeCouponUser.setCouponId(storeCoupon.getId());
        storeCouponUser.setUid(uid);
        storeCouponUser.setName(storeCoupon.getName());
        storeCouponUser.setMoney(storeCoupon.getMoney());
        storeCouponUser.setMinPrice(storeCoupon.getMinPrice());
        storeCouponUser.setStartTime(storeCoupon.getUseStartTime());
        storeCouponUser.setEndTime(storeCoupon.getUseEndTime());
        storeCouponUser.setUseType(storeCoupon.getUseType());
        storeCouponUser.setType(CouponConstants.STORE_COUPON_USER_TYPE_BUY);
        if (storeCoupon.getUseType() > 1) {
            storeCouponUser.setPrimaryKey(storeCoupon.getPrimaryKey());
        }
        record.set("status", "ok");
        record.set("storeCouponUser", storeCouponUser);
        record.set("isLimited", storeCoupon.getIsLimited());
        return record;
    }

    /**
     * ??????uid????????????
     * @param uid uid
     * @param pageParamRequest ????????????
     * @return ???????????????
     */
    @Override
    public List<StoreCouponUser> findListByUid(Integer uid, PageParamRequest pageParamRequest) {
        PageHelper.startPage(pageParamRequest.getPage(), pageParamRequest.getLimit());

        //??? StoreCouponUser ?????????????????????
        LambdaQueryWrapper<StoreCouponUser> lambdaQueryWrapper = new LambdaQueryWrapper<>();

        lambdaQueryWrapper.eq(StoreCouponUser::getUid, uid);
        lambdaQueryWrapper.orderByDesc(StoreCouponUser::getId);
        return dao.selectList(lambdaQueryWrapper);
    }

    /**
     * ???????????????????????????
     * @param uid ??????uid
     * @return Integer
     */
    @Override
    public Integer getUseCount(Integer uid) {
        LambdaQueryWrapper<StoreCouponUser> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(StoreCouponUser::getUid, uid);
        lambdaQueryWrapper.eq(StoreCouponUser::getStatus, 0);
        List<StoreCouponUser> storeCouponUserList = dao.selectList(lambdaQueryWrapper);
        if (CollUtil.isEmpty(storeCouponUserList)) {
            return 0;
        }
        Date date = DateUtil.nowDateTime();
        for (int i = 0; i < storeCouponUserList.size();) {
            StoreCouponUser couponUser = storeCouponUserList.get(i);
            //??????????????????????????????
            if (ObjectUtil.isNotNull(couponUser.getStartTime()) && ObjectUtil.isNotNull(couponUser.getEndTime())) {
                if (date.compareTo(couponUser.getEndTime()) >= 0) {
                    storeCouponUserList.remove(i);
                    continue;
                }
            }
            i++;
        }
        return CollUtil.isEmpty(storeCouponUserList) ? 0 : storeCouponUserList.size();
    }

    /**
     * ?????????????????????
     * @param type ?????????usable-?????????unusable-?????????
     * @param pageParamRequest ????????????
     * @return CommonPage<StoreCouponUserResponse>
     */
    @Override
    public CommonPage<StoreCouponUserResponse> getMyCouponList(String type, PageParamRequest pageParamRequest) {
        Integer userId = userService.getUserIdException();

        List<StoreCouponUser> couponUserList = getH5List(type, userId, pageParamRequest);

        if (CollUtil.isEmpty(couponUserList)) {
            return null;
        }
        Date date = DateUtil.nowDateTime();
        List<StoreCouponUserResponse> responseList = CollUtil.newArrayList();
        for (StoreCouponUser storeCouponUser :couponUserList) {
            StoreCouponUserResponse storeCouponUserResponse = new StoreCouponUserResponse();
            BeanUtils.copyProperties(storeCouponUser, storeCouponUserResponse);
            String validStr = "usable";// ??????
            if (storeCouponUser.getStatus().equals(CouponConstants.STORE_COUPON_USER_STATUS_USED)) {
                validStr = "unusable";// ??????
            }
            if (storeCouponUser.getStatus().equals(CouponConstants.STORE_COUPON_USER_STATUS_LAPSED)) {
                validStr = "overdue";// ??????
            }

            //??????????????????????????????
            if (null != storeCouponUserResponse.getStartTime() && null != storeCouponUserResponse.getEndTime()) {
                if (storeCouponUserResponse.getStartTime().compareTo(date) > 0) {
                    validStr = "notStart";// ?????????
                }
                if (date.compareTo(storeCouponUserResponse.getEndTime()) >= 0) {
                    validStr = "overdue";// ??????
                }
            }
            storeCouponUserResponse.setValidStr(validStr);

            // ??????????????????????????????????????????
            storeCouponUserResponse.setUseStartTimeStr(DateUtil.dateToStr(storeCouponUserResponse.getStartTime(), Constants.DATE_FORMAT_DATE));
            storeCouponUserResponse.setUseEndTimeStr(DateUtil.dateToStr(storeCouponUserResponse.getEndTime(), Constants.DATE_FORMAT_DATE));
            responseList.add(storeCouponUserResponse);
        }
        return CommonPage.restPage(responseList);
    }

    private void getPrimaryKeySql(LambdaQueryWrapper<StoreCouponUser> lambdaQueryWrapper, String productIdStr) {
        if (StringUtils.isBlank(productIdStr)) {
            return;
        }

        List<Integer> categoryIdList = storeProductService.getSecondaryCategoryByProductId(productIdStr);
        String categoryIdStr = categoryIdList.stream().map(Object::toString).collect(Collectors.joining(","));
        lambdaQueryWrapper.and(i -> i.and(
                //?????????  ?????????  ?????????
                t -> t.eq(StoreCouponUser::getUseType, 1)
                        .or(p -> p.eq(StoreCouponUser::getUseType , 2).apply(StrUtil.format(" primary_key in ({})", productIdStr)))
                        .or(c -> c.eq(StoreCouponUser::getUseType , 3).apply(StrUtil.format(" primary_key in ({})", categoryIdStr)))

        ));
    }
}

