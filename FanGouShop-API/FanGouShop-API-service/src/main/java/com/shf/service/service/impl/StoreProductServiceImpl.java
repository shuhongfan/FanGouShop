package com.shf.service.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shf.common.constants.Constants;
import com.shf.common.exception.CrmebException;
import com.shf.common.model.category.Category;
import com.shf.common.model.coupon.StoreCoupon;
import com.shf.common.model.product.*;
import com.shf.common.request.*;
import com.shf.common.response.*;
import com.shf.common.page.CommonPage;
import com.shf.common.utils.CrmebUtil;
import com.shf.common.utils.DateUtil;
import com.shf.common.utils.RedisUtil;
import com.shf.common.vo.MyRecord;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.shf.service.dao.StoreProductDao;
import com.shf.service.delete.ProductUtils;
import com.shf.service.service.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class StoreProductServiceImpl extends ServiceImpl<StoreProductDao, StoreProduct>
        implements StoreProductService {

    @Resource
    private StoreProductDao dao;

    @Autowired
    private StoreProductAttrService attrService;

    @Autowired
    private StoreProductAttrValueService storeProductAttrValueService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private StoreProductDescriptionService storeProductDescriptionService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private StoreProductRelationService storeProductRelationService;

    @Autowired
    private SystemAttachmentService systemAttachmentService;

    @Autowired
    private StoreProductAttrResultService storeProductAttrResultService;

    @Autowired
    private StoreProductCouponService storeProductCouponService;

    @Autowired
    private StoreCouponService storeCouponService;

    @Autowired
    private ProductUtils productUtils;

    @Autowired
    private StoreBargainService storeBargainService;

    @Autowired
    private StoreCombinationService storeCombinationService;

    @Autowired
    private StoreSeckillService storeSeckillService;

    @Autowired
    private OnePassService onePassService;

    @Autowired
    private StoreCartService storeCartService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final Logger logger = LoggerFactory.getLogger(StoreProductServiceImpl.class);

    /**
     * ??????????????????Admin
     * @param request ????????????
     * @param pageParamRequest ????????????
     * @return PageInfo
     */
    @Override
    public PageInfo<StoreProductResponse> getAdminList(StoreProductSearchRequest request, PageParamRequest pageParamRequest) {
        //??? StoreProduct ?????????????????????
        LambdaQueryWrapper<StoreProduct> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        //????????????
        switch (request.getType()) {
            case 1:
                //????????????????????????
                lambdaQueryWrapper.eq(StoreProduct::getIsShow, true);
                lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
                lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                break;
            case 2:
                //????????????????????????
                lambdaQueryWrapper.eq(StoreProduct::getIsShow, false);
                lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
                lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                break;
            case 3:
                //?????????
                lambdaQueryWrapper.le(StoreProduct::getStock, 0);
                lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
                lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                break;
            case 4:
                //????????????
                Integer stock = Integer.parseInt(systemConfigService.getValueByKey("store_stock"));
                lambdaQueryWrapper.le(StoreProduct::getStock, stock);
                lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
                lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                break;
            case 5:
                //?????????
                lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, true);
                lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                break;
            default:
                break;
        }

        //???????????????
        if (StrUtil.isNotBlank(request.getKeywords())) {
            lambdaQueryWrapper.and(i -> i
                    .or().eq(StoreProduct::getId, request.getKeywords())
                    .or().like(StoreProduct::getStoreName, request.getKeywords())
                    .or().like(StoreProduct::getKeyword, request.getKeywords()));
        }
        lambdaQueryWrapper.apply(StringUtils.isNotBlank(request.getCateId()), "FIND_IN_SET ('" + request.getCateId() + "', cate_id)");
        lambdaQueryWrapper.orderByDesc(StoreProduct::getSort).orderByDesc(StoreProduct::getId);

        Page<StoreProduct> storeProductPage = PageHelper.startPage(pageParamRequest.getPage(), pageParamRequest.getLimit());
        List<StoreProduct> storeProducts = dao.selectList(lambdaQueryWrapper);
        List<StoreProductResponse> storeProductResponses = new ArrayList<>();
        for (StoreProduct product : storeProducts) {
            StoreProductResponse storeProductResponse = new StoreProductResponse();
            BeanUtils.copyProperties(product, storeProductResponse);
            StoreProductAttr storeProductAttrPram = new StoreProductAttr();
            storeProductAttrPram.setProductId(product.getId()).setType(Constants.PRODUCT_TYPE_NORMAL);
            List<StoreProductAttr> attrs = attrService.getByEntity(storeProductAttrPram);

            if (attrs.size() > 0) {
                storeProductResponse.setAttr(attrs);
            }
            List<StoreProductAttrValueResponse> storeProductAttrValueResponse = new ArrayList<>();

            StoreProductAttrValue storeProductAttrValuePram = new StoreProductAttrValue();
            storeProductAttrValuePram.setProductId(product.getId()).setType(Constants.PRODUCT_TYPE_NORMAL);
            List<StoreProductAttrValue> storeProductAttrValues = storeProductAttrValueService.getByEntity(storeProductAttrValuePram);
            storeProductAttrValues.stream().map(e->{
                StoreProductAttrValueResponse response = new StoreProductAttrValueResponse();
                BeanUtils.copyProperties(e,response);
                storeProductAttrValueResponse.add(response);
                return e;
            }).collect(Collectors.toList());
            storeProductResponse.setAttrValue(storeProductAttrValueResponse);
            // ???????????????
            StoreProductDescription sd = storeProductDescriptionService.getOne(
                    new LambdaQueryWrapper<StoreProductDescription>()
                            .eq(StoreProductDescription::getProductId, product.getId())
                            .eq(StoreProductDescription::getType, Constants.PRODUCT_TYPE_NORMAL));
            if (null != sd) {
                storeProductResponse.setContent(null == sd.getDescription()?"":sd.getDescription());
            }
            // ??????????????????
            List<Category> cg = categoryService.getByIds(CrmebUtil.stringToArray(product.getCateId()));
            if (CollUtil.isEmpty(cg)) {
                storeProductResponse.setCateValues("");
            } else {
                storeProductResponse.setCateValues(cg.stream().map(Category::getName).collect(Collectors.joining(",")));
            }

            storeProductResponse.setCollectCount(
                    storeProductRelationService.getList(product.getId(),"collect").size());
            storeProductResponses.add(storeProductResponse);
        }
        // ??????sql????????????????????????
        return CommonPage.copyPageInfo(storeProductPage, storeProductResponses);
    }

    /**
     * ????????????id????????????
     * @param productIds id??????
     * @return
     */
    @Override
    public List<StoreProduct> getListInIds(List<Integer> productIds) {
        LambdaQueryWrapper<StoreProduct> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.in(StoreProduct::getId,productIds);
        lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
        return dao.selectList(lambdaQueryWrapper);
    }

    /**
     * ????????????
     * @param request ????????????request??????
     * @return ????????????
     */
    @Override
    public Boolean save(StoreProductAddRequest request) {
        // ?????????????????????????????????
        if (!request.getSpecType()) {
            if (request.getAttrValue().size() > 1) {
                throw new CrmebException("????????????????????????????????????1");
            }
        }

        StoreProduct storeProduct = new StoreProduct();
        BeanUtils.copyProperties(request, storeProduct);
        storeProduct.setId(null);
        storeProduct.setAddTime(DateUtil.getNowTime());
        storeProduct.setIsShow(false);

        // ??????Acticity??????
        storeProduct.setActivity(getProductActivityStr(request.getActivity()));

        //??????
        storeProduct.setImage(systemAttachmentService.clearPrefix(storeProduct.getImage()));

        //?????????
        storeProduct.setSliderImage(systemAttachmentService.clearPrefix(storeProduct.getSliderImage()));
        // ?????????
        if (StrUtil.isNotEmpty(storeProduct.getFlatPattern())) {
            storeProduct.setFlatPattern(systemAttachmentService.clearPrefix(storeProduct.getFlatPattern()));
        }

        List<StoreProductAttrValueAddRequest> attrValueAddRequestList = request.getAttrValue();
        //????????????
        StoreProductAttrValueAddRequest minAttrValue = attrValueAddRequestList.stream().min(Comparator.comparing(StoreProductAttrValueAddRequest::getPrice)).get();
        storeProduct.setPrice(minAttrValue.getPrice());
        storeProduct.setOtPrice(minAttrValue.getOtPrice());
        storeProduct.setCost(minAttrValue.getCost());
        storeProduct.setStock(attrValueAddRequestList.stream().mapToInt(StoreProductAttrValueAddRequest::getStock).sum());

        // ???????????????
        if (ObjectUtil.isNull(request.getSort())) {
            storeProduct.setSort(0);
        }
        if (ObjectUtil.isNull(request.getIsHot())) {
            storeProduct.setIsHot(false);
        }
        if (ObjectUtil.isNull(request.getIsBenefit())) {
            storeProduct.setIsBenefit(false);
        }
        if (ObjectUtil.isNull(request.getIsBest())) {
            storeProduct.setIsBest(false);
        }
        if (ObjectUtil.isNull(request.getIsNew())) {
            storeProduct.setIsNew(false);
        }
        if (ObjectUtil.isNull(request.getIsGood())) {
            storeProduct.setIsGood(false);
        }
        if (ObjectUtil.isNull(request.getGiveIntegral())) {
            storeProduct.setGiveIntegral(0);
        }
        if (ObjectUtil.isNull(request.getFicti())) {
            storeProduct.setFicti(0);
        }

        List<StoreProductAttrAddRequest> addRequestList = request.getAttr();
        List<StoreProductAttr> attrList = addRequestList.stream().map(e -> {
            StoreProductAttr attr = new StoreProductAttr();
            BeanUtils.copyProperties(e, attr);
            attr.setType(Constants.PRODUCT_TYPE_NORMAL);
            return attr;
        }).collect(Collectors.toList());

        List<StoreProductAttrValue> attrValueList = attrValueAddRequestList.stream().map(e -> {
            StoreProductAttrValue attrValue = new StoreProductAttrValue();
            BeanUtils.copyProperties(e, attrValue);
            attrValue.setId(null);
            attrValue.setSuk(getSku(e.getAttrValue()));
            attrValue.setQuota(0);
            attrValue.setQuotaShow(0);
            attrValue.setType(Constants.PRODUCT_TYPE_NORMAL);
            attrValue.setImage(systemAttachmentService.clearPrefix(e.getImage()));
            return attrValue;
        }).collect(Collectors.toList());

        // ???????????????
        StoreProductDescription spd = new StoreProductDescription();
        spd.setDescription(request.getContent().length() > 0 ? systemAttachmentService.clearPrefix(request.getContent()) : "");
        spd.setType(Constants.PRODUCT_TYPE_NORMAL);

        Boolean execute = transactionTemplate.execute(e -> {
            save(storeProduct);

            attrList.forEach(attr -> attr.setProductId(storeProduct.getId()));
            attrValueList.forEach(value -> value.setProductId(storeProduct.getId()));
            attrService.saveBatch(attrList);
            storeProductAttrValueService.saveBatch(attrValueList);

            spd.setProductId(storeProduct.getId());
            storeProductDescriptionService.deleteByProductId(storeProduct.getId(), Constants.PRODUCT_TYPE_NORMAL);
            storeProductDescriptionService.save(spd);

            if (CollUtil.isNotEmpty(request.getCouponIds())) {
                List<StoreProductCoupon> couponList = new ArrayList<>();
                for (Integer couponId : request.getCouponIds()) {
                    StoreProductCoupon spc = new StoreProductCoupon(storeProduct.getId(), couponId, DateUtil.getNowTime());
                    couponList.add(spc);
                }
                storeProductCouponService.saveBatch(couponList);
            }
            return Boolean.TRUE;
        });

        return execute;
    }

    /**
     * ??????sku
     * @param attrValue json?????????
     * @return sku
     */
    private String getSku(String attrValue) {
        LinkedHashMap<String, String> linkedHashMap = JSONObject.parseObject(attrValue, LinkedHashMap.class, Feature.OrderedField);
        Iterator<Map.Entry<String, String>> iterator = linkedHashMap.entrySet().iterator();
        List<String> strings = CollUtil.newArrayList();
        while (iterator.hasNext()) {
            Map.Entry<String, String> next = iterator.next();
            strings.add(next.getValue());
        }
//        List<String> strings = jsonObject.values().stream().map(o -> (String) o).collect(Collectors.toList());
        return String.join(",", strings);
    }

    /**
     * ?????????????????????
     * @param activityList ????????????
     * @return ?????????????????????
     */
    private String getProductActivityStr(List<String> activityList) {
        if (CollUtil.isEmpty(activityList)) {
            return "0, 1, 2, 3";
        }
        List<Integer> activities = new ArrayList<>();
        activityList.forEach(e->{
            switch (e) {
                case Constants.PRODUCT_TYPE_NORMAL_STR:
                    activities.add(Constants.PRODUCT_TYPE_NORMAL);
                    break;
                case Constants.PRODUCT_TYPE_SECKILL_STR:
                    activities.add(Constants.PRODUCT_TYPE_SECKILL);
                    break;
                case Constants.PRODUCT_TYPE_BARGAIN_STR:
                    activities.add(Constants.PRODUCT_TYPE_BARGAIN);
                    break;
                case Constants.PRODUCT_TYPE_PINGTUAN_STR:
                    activities.add(Constants.PRODUCT_TYPE_PINGTUAN);
                    break;
            }
        });
        return activities.stream().map(Object::toString).collect(Collectors.joining(","));
    }

    /**
     * ??????????????????
     * @param storeProductRequest ????????????
     * @return ????????????
     */
    @Override
    public Boolean update(StoreProductAddRequest storeProductRequest) {
        if (ObjectUtil.isNull(storeProductRequest.getId())) {
            throw new CrmebException("??????ID????????????");
        }

        if (!storeProductRequest.getSpecType()) {
            if (storeProductRequest.getAttrValue().size() > 1) {
                throw new CrmebException("????????????????????????????????????1");
            }
        }

        StoreProduct tempProduct = getById(storeProductRequest.getId());
        if (ObjectUtil.isNull(tempProduct)) {
            throw new CrmebException("???????????????");
        }
        if (tempProduct.getIsRecycle() || tempProduct.getIsDel()) {
            throw new CrmebException("???????????????");
        }
        if (tempProduct.getIsShow()) {
            throw new CrmebException("????????????????????????????????????");
        }
        // ???????????????????????????????????????????????????
//        if (storeSeckillService.isExistByProductId(storeProductRequest.getId())) {
//            throw new CrmebException("?????????????????????????????????????????????????????????????????????????????????");
//        }
//        if (storeBargainService.isExistByProductId(storeProductRequest.getId())) {
//            throw new CrmebException("?????????????????????????????????????????????????????????????????????????????????");
//        }
//        if (storeCombinationService.isExistByProductId(storeProductRequest.getId())) {
//            throw new CrmebException("?????????????????????????????????????????????????????????????????????????????????");
//        }

        StoreProduct storeProduct = new StoreProduct();
        BeanUtils.copyProperties(storeProductRequest, storeProduct);

        // ??????Activity??????
        storeProduct.setActivity(getProductActivityStr(storeProductRequest.getActivity()));

        //??????
        storeProduct.setImage(systemAttachmentService.clearPrefix(storeProduct.getImage()));

        //?????????
        storeProduct.setSliderImage(systemAttachmentService.clearPrefix(storeProduct.getSliderImage()));

        List<StoreProductAttrValueAddRequest> attrValueAddRequestList = storeProductRequest.getAttrValue();
        //????????????
        StoreProductAttrValueAddRequest minAttrValue = attrValueAddRequestList.stream().min(Comparator.comparing(StoreProductAttrValueAddRequest::getPrice)).get();
        storeProduct.setPrice(minAttrValue.getPrice());
        storeProduct.setOtPrice(minAttrValue.getOtPrice());
        storeProduct.setCost(minAttrValue.getCost());
        storeProduct.setStock(attrValueAddRequestList.stream().mapToInt(StoreProductAttrValueAddRequest::getStock).sum());

        // attr??????
        List<StoreProductAttrAddRequest> addRequestList = storeProductRequest.getAttr();
        List<StoreProductAttr> attrAddList = CollUtil.newArrayList();
        List<StoreProductAttr> attrUpdateList = CollUtil.newArrayList();
        addRequestList.forEach(e -> {
            StoreProductAttr attr = new StoreProductAttr();
            BeanUtils.copyProperties(e, attr);
            if (ObjectUtil.isNull(attr.getId())) {
                attr.setProductId(storeProduct.getId());
                attr.setType(Constants.PRODUCT_TYPE_NORMAL);
                attrAddList.add(attr);
            } else {
                attr.setIsDel(false);
                attrUpdateList.add(attr);
            }
        });

        // attrValue??????
        List<StoreProductAttrValue> attrValueAddList = CollUtil.newArrayList();
        List<StoreProductAttrValue> attrValueUpdateList = CollUtil.newArrayList();
        attrValueAddRequestList.forEach(e -> {
            StoreProductAttrValue attrValue = new StoreProductAttrValue();
            BeanUtils.copyProperties(e, attrValue);
            attrValue.setSuk(getSku(e.getAttrValue()));
            attrValue.setImage(systemAttachmentService.clearPrefix(e.getImage()));
            if (ObjectUtil.isNull(attrValue.getId()) || attrValue.getId().equals(0)) {
                attrValue.setId(null);
                attrValue.setProductId(storeProduct.getId());
                attrValue.setQuota(0);
                attrValue.setQuotaShow(0);
                attrValue.setType(Constants.PRODUCT_TYPE_NORMAL);
                attrValueAddList.add(attrValue);
            } else {
                attrValue.setIsDel(false);
                attrValueUpdateList.add(attrValue);
            }
        });

        // ???????????????
        StoreProductDescription spd = new StoreProductDescription();
        spd.setDescription(storeProductRequest.getContent().length() > 0 ? systemAttachmentService.clearPrefix(storeProductRequest.getContent()) : "");
        spd.setType(Constants.PRODUCT_TYPE_NORMAL);
        spd.setProductId(storeProduct.getId());

        Boolean execute = transactionTemplate.execute(e -> {
            dao.updateById(storeProduct);

            // ???????????????attr+value
            attrService.deleteByProductIdAndType(storeProduct.getId(), Constants.PRODUCT_TYPE_NORMAL);
            storeProductAttrValueService.deleteByProductIdAndType(storeProduct.getId(), Constants.PRODUCT_TYPE_NORMAL);

            if (CollUtil.isNotEmpty(attrAddList)) {
                attrService.saveBatch(attrAddList);
            }
            if (CollUtil.isNotEmpty(attrUpdateList)) {
                attrService.saveOrUpdateBatch(attrUpdateList);
            }

            if (CollUtil.isNotEmpty(attrValueAddList)) {
                storeProductAttrValueService.saveBatch(attrValueAddList);
            }
            if (CollUtil.isNotEmpty(attrValueUpdateList)) {
                storeProductAttrValueService.saveOrUpdateBatch(attrValueUpdateList);
            }

            storeProductDescriptionService.deleteByProductId(storeProduct.getId(), Constants.PRODUCT_TYPE_NORMAL);
            storeProductDescriptionService.save(spd);

            if (CollUtil.isNotEmpty(storeProductRequest.getCouponIds())) {
                storeProductCouponService.deleteByProductId(storeProduct.getId());
                List<StoreProductCoupon> couponList = new ArrayList<>();
                for (Integer couponId : storeProductRequest.getCouponIds()) {
                    StoreProductCoupon spc = new StoreProductCoupon(storeProduct.getId(), couponId, DateUtil.getNowTime());
                    couponList.add(spc);
                }
                storeProductCouponService.saveBatch(couponList);
            } else {
                storeProductCouponService.deleteByProductId(storeProduct.getId());
            }

            return Boolean.TRUE;
        });

        return execute;
    }

    /**
     * ????????????
     * @param id ??????id
     * @return ????????????
     */
    @Override
    public StoreProductResponse getByProductId(Integer id) {
        StoreProduct storeProduct = dao.selectById(id);
        if (null == storeProduct) throw new CrmebException("???????????????????????????");
        StoreProductResponse storeProductResponse = new StoreProductResponse();
        BeanUtils.copyProperties(storeProduct, storeProductResponse);
        StoreProductAttr spaPram = new StoreProductAttr();
        spaPram.setProductId(storeProduct.getId()).setType(Constants.PRODUCT_TYPE_NORMAL);
        storeProductResponse.setAttr(attrService.getByEntity(spaPram));

        // ??????????????????????????????
        storeProductResponse.setActivityH5(productUtils.getProductCurrentActivity(storeProduct));
        StoreProductAttrValue spavPram = new StoreProductAttrValue();
        spavPram.setProductId(id).setType(Constants.PRODUCT_TYPE_NORMAL);
        List<StoreProductAttrValue> storeProductAttrValues = storeProductAttrValueService.getByEntity(spavPram);
        // ??????attrValue???????????????????????????
        List<HashMap<String, Object>> attrValues = new ArrayList<>();

        if (storeProduct.getSpecType()) {
            // ???????????????????????????
            StoreProductAttrResult sparPram = new StoreProductAttrResult();
            sparPram.setProductId(storeProduct.getId()).setType(Constants.PRODUCT_TYPE_NORMAL);
            List<StoreProductAttrResult> attrResults = storeProductAttrResultService.getByEntity(sparPram);
            if (null == attrResults || attrResults.size() == 0) {
                throw new CrmebException("????????????????????????");
            }
            StoreProductAttrResult attrResult = attrResults.get(0);
            //PC ?????????skuAttrInfo
            List<StoreProductAttrValueRequest> storeProductAttrValueRequests =
                    com.alibaba.fastjson.JSONObject.parseArray(attrResult.getResult(), StoreProductAttrValueRequest.class);
            if (null != storeProductAttrValueRequests) {
                for (int i = 0; i < storeProductAttrValueRequests.size(); i++) {
//                    StoreProductAttrValueRequest storeProductAttrValueRequest = storeProductAttrValueRequests.get(i);
                    HashMap<String, Object> attrValue = new HashMap<>();
                    String currentSku = storeProductAttrValues.get(i).getSuk();
                    List<StoreProductAttrValue> hasCurrentSku =
                            storeProductAttrValues.stream().filter(e -> e.getSuk().equals(currentSku)).collect(Collectors.toList());
                    StoreProductAttrValue currentAttrValue = hasCurrentSku.get(0);
                    attrValue.put("id", hasCurrentSku.size() > 0 ? hasCurrentSku.get(0).getId():0);
                    attrValue.put("image", currentAttrValue.getImage());
                    attrValue.put("cost", currentAttrValue.getCost());
                    attrValue.put("price", currentAttrValue.getPrice());
                    attrValue.put("otPrice", currentAttrValue.getOtPrice());
                    attrValue.put("stock", currentAttrValue.getStock());
                    attrValue.put("barCode", currentAttrValue.getBarCode());
                    attrValue.put("weight", currentAttrValue.getWeight());
                    attrValue.put("volume", currentAttrValue.getVolume());
                    attrValue.put("suk", currentSku);
                    attrValue.put("attrValue", JSON.parseObject(storeProductAttrValues.get(i).getAttrValue(), Feature.OrderedField));
                    attrValue.put("brokerage", currentAttrValue.getBrokerage());
                    attrValue.put("brokerageTwo", currentAttrValue.getBrokerageTwo());
                    String[] skus = currentSku.split(",");
                    for (int k = 0; k < skus.length; k++) {
                        attrValue.put("value"+k,skus[k]);
                    }
                    attrValues.add(attrValue);
                }
            }
        }

        // H5 ???????????????skuList
        List<StoreProductAttrValueResponse> sPAVResponses = new ArrayList<>();

        for (StoreProductAttrValue storeProductAttrValue : storeProductAttrValues) {
            StoreProductAttrValueResponse atr = new StoreProductAttrValueResponse();
            BeanUtils.copyProperties(storeProductAttrValue,atr);
            sPAVResponses.add(atr);
        }
        storeProductResponse.setAttrValues(attrValues);
        storeProductResponse.setAttrValue(sPAVResponses);
//        if (null != storeProductAttrResult) {
            StoreProductDescription sd = storeProductDescriptionService.getOne(
                    new LambdaQueryWrapper<StoreProductDescription>()
                            .eq(StoreProductDescription::getProductId, storeProduct.getId())
                            .eq(StoreProductDescription::getType, Constants.PRODUCT_TYPE_NORMAL));
            if (null != sd) {
                storeProductResponse.setContent(null == sd.getDescription()?"":sd.getDescription());
            }
//        }
        // ???????????????????????????
        List<StoreProductCoupon> storeProductCoupons = storeProductCouponService.getListByProductId(storeProduct.getId());
        if (null != storeProductCoupons && storeProductCoupons.size() > 0) {
            List<Integer> ids = storeProductCoupons.stream().map(StoreProductCoupon::getIssueCouponId).collect(Collectors.toList());
            List<StoreCoupon> shipCoupons = storeCouponService.getByIds(ids);
            storeProductResponse.setCoupons(shipCoupons);
            storeProductResponse.setCouponIds(ids);
        }
        return storeProductResponse;
    }

    /**
     * ???????????????????????????
     * @param id ??????id
     * @return StoreProductInfoResponse
     */
    @Override
    public StoreProductInfoResponse getInfo(Integer id) {
        StoreProduct storeProduct = dao.selectById(id);
        if (ObjectUtil.isNull(storeProduct)) {
            throw new CrmebException("???????????????????????????");
        }

        StoreProductInfoResponse storeProductResponse = new StoreProductInfoResponse();
        BeanUtils.copyProperties(storeProduct, storeProductResponse);

        // ??????????????????????????????
        List<String> activityList = getProductActivityList(storeProduct.getActivity());
        storeProductResponse.setActivity(activityList);

        List<StoreProductAttr> attrList = attrService.getListByProductIdAndType(storeProduct.getId(), Constants.PRODUCT_TYPE_NORMAL);
        storeProductResponse.setAttr(attrList);

        List<StoreProductAttrValue> attrValueList = storeProductAttrValueService.getListByProductIdAndType(storeProduct.getId(), Constants.PRODUCT_TYPE_NORMAL);
        List<AttrValueResponse> valueResponseList = attrValueList.stream().map(e -> {
            AttrValueResponse valueResponse = new AttrValueResponse();
            BeanUtils.copyProperties(e, valueResponse);
            return valueResponse;
        }).collect(Collectors.toList());
        storeProductResponse.setAttrValue(valueResponseList);

        StoreProductDescription sd = storeProductDescriptionService.getByProductIdAndType(storeProduct.getId(), Constants.PRODUCT_TYPE_NORMAL);
        if (ObjectUtil.isNotNull(sd)) {
            storeProductResponse.setContent(ObjectUtil.isNull(sd.getDescription()) ? "" : sd.getDescription());
        }

        // ???????????????????????????
        List<StoreProductCoupon> storeProductCoupons = storeProductCouponService.getListByProductId(storeProduct.getId());
        if (CollUtil.isNotEmpty(storeProductCoupons)) {
            List<Integer> ids = storeProductCoupons.stream().map(StoreProductCoupon::getIssueCouponId).collect(Collectors.toList());
            storeProductResponse.setCouponIds(ids);
        }
        return storeProductResponse;
    }

    /**
     * ????????????????????????
     * @param activityStr ?????????????????????
     * @return ????????????????????????
     */
    private List<String> getProductActivityList(String activityStr) {
        List<String> activityList = CollUtil.newArrayList();
        if (activityStr.equals("0, 1, 2, 3")) {
            activityList.add(Constants.PRODUCT_TYPE_NORMAL_STR);
            activityList.add(Constants.PRODUCT_TYPE_SECKILL_STR);
            activityList.add(Constants.PRODUCT_TYPE_BARGAIN_STR);
            activityList.add(Constants.PRODUCT_TYPE_PINGTUAN_STR);
            return activityList;
        }
        String[] split = activityStr.split(",");
        for (String s : split) {
            Integer integer = Integer.valueOf(s);
            if (integer.equals(Constants.PRODUCT_TYPE_NORMAL)) {
                activityList.add(Constants.PRODUCT_TYPE_NORMAL_STR);
            }
            if (integer.equals(Constants.PRODUCT_TYPE_SECKILL)) {
                activityList.add(Constants.PRODUCT_TYPE_SECKILL_STR);
            }
            if (integer.equals(Constants.PRODUCT_TYPE_BARGAIN)) {
                activityList.add(Constants.PRODUCT_TYPE_BARGAIN_STR);
            }
            if (integer.equals(Constants.PRODUCT_TYPE_PINGTUAN)) {
                activityList.add(Constants.PRODUCT_TYPE_PINGTUAN_STR);
            }
        }
        return activityList;
    }

    /**
     * ????????????tabs?????????????????????????????????
     * @return List
     */
    @Override
    public List<StoreProductTabsHeader> getTabsHeader() {
        List<StoreProductTabsHeader> headers = new ArrayList<>();
        StoreProductTabsHeader header1 = new StoreProductTabsHeader(0,"???????????????",1);
        StoreProductTabsHeader header2 = new StoreProductTabsHeader(0,"???????????????",2);
        StoreProductTabsHeader header3 = new StoreProductTabsHeader(0,"??????????????????",3);
        StoreProductTabsHeader header4 = new StoreProductTabsHeader(0,"????????????",4);
        StoreProductTabsHeader header5 = new StoreProductTabsHeader(0,"???????????????",5);
        headers.add(header1);
        headers.add(header2);
        headers.add(header3);
        headers.add(header4);
        headers.add(header5);
        for (StoreProductTabsHeader h : headers) {
            LambdaQueryWrapper<StoreProduct> lambdaQueryWrapper = new LambdaQueryWrapper<>();
            switch (h.getType()) {
                case 1:
                    //????????????????????????
                    lambdaQueryWrapper.eq(StoreProduct::getIsShow, true);
                    lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
                    lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                    break;
                case 2:
                    //????????????????????????
                    lambdaQueryWrapper.eq(StoreProduct::getIsShow, false);
                    lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
                    lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                    break;
                case 3:
                    //?????????
                    lambdaQueryWrapper.le(StoreProduct::getStock, 0);
                    lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
                    lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                    break;
                case 4:
                    //????????????
                    Integer stock = Integer.parseInt(systemConfigService.getValueByKey("store_stock"));
                    lambdaQueryWrapper.le(StoreProduct::getStock, stock);
                    lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
                    lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                    break;
                case 5:
                    //?????????
                    lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, true);
                    lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
                    break;
                default:
                    break;
            }
            List<StoreProduct> storeProducts = dao.selectList(lambdaQueryWrapper);
            h.setCount(storeProducts.size());
        }

        return headers;
    }

    /**
     * ??????????????????????????????
     */
    @Override
    public void consumeProductStock() {
        String redisKey = Constants.PRODUCT_STOCK_UPDATE;
        Long size = redisUtil.getListSize(redisKey);
        logger.info("StoreProductServiceImpl.doProductStock | size:" + size);
        if (size < 1) {
            return;
        }
        for (int i = 0; i < size; i++) {
            //??????10????????????????????????????????????????????????
            Object data = redisUtil.getRightPop(redisKey, 10L);
            if (null == data) {
                continue;
            }
            try {
                StoreProductStockRequest storeProductStockRequest =
                        com.alibaba.fastjson.JSONObject.toJavaObject(com.alibaba.fastjson.JSONObject.parseObject(data.toString()), StoreProductStockRequest.class);
                boolean result = doProductStock(storeProductStockRequest);
                if (!result) {
                    redisUtil.lPush(redisKey, data);
                }
            } catch (Exception e) {
                redisUtil.lPush(redisKey, data);
            }
        }
    }

    /**
     * ????????????id??????????????????
     * @param productIdStr String ????????????
     * @return List<Integer>
     */
    @Override
    public List<Integer> getSecondaryCategoryByProductId(String productIdStr) {
        List<Integer> idList = new ArrayList<>();

        if (StringUtils.isBlank(productIdStr)) {
            return idList;
        }
        List<Integer> productIdList = CrmebUtil.stringToArray(productIdStr);
        LambdaQueryWrapper<StoreProduct> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.in(StoreProduct::getId, productIdList);
        List<StoreProduct> productList = dao.selectList(lambdaQueryWrapper);
        if (productIdList.size() < 1) {
            return idList;
        }

        //??????????????????id????????????
        for (StoreProduct storeProduct : productList) {
            List<Integer> categoryIdList = CrmebUtil.stringToArray(storeProduct.getCateId());
            idList.addAll(categoryIdList);
        }

        //??????
        List<Integer> cateIdList = idList.stream().distinct().collect(Collectors.toList());
        if (cateIdList.size() < 1) {
            return idList;
        }

        //???????????????????????????
        List<Category> categoryList = categoryService.getByIds(cateIdList);
        if (categoryList.size() < 1) {
            return idList;
        }

        for (Category category: categoryList) {
            List<Integer> parentIdList = CrmebUtil.stringToArrayByRegex(category.getPath(), "/");
            if (parentIdList.size() > 2) {
                Integer secondaryCategoryId = parentIdList.get(2);
                if (secondaryCategoryId > 0) {
                    idList.add(secondaryCategoryId);
                }
            }
        }
        return idList;
    }

    /**
     * ??????????????????url??????????????????
     * @param url ???????????????url
     * @param tag 1=?????????2=?????????3=?????????4=???????????? 5=??????
     * @return StoreProductRequest
     */
    @Override
    public StoreProductRequest importProductFromUrl(String url, int tag) {
        StoreProductRequest productRequest = null;
        try {
            switch (tag) {
                case 1:
                    productRequest = productUtils.getTaobaoProductInfo(url,tag);
                    break;
                case 2:
                    productRequest = productUtils.getJDProductInfo(url,tag);
                    break;
                case 3:
                    productRequest = productUtils.getSuningProductInfo(url,tag);
                    break;
                case 4:
                    productRequest = productUtils.getPddProductInfo(url,tag);
                    break;
                case 5:
                    productRequest = productUtils.getTmallProductInfo(url,tag);
                    break;
            }
        } catch (Exception e) {
            throw new CrmebException("??????URL??????????????????????????????????????????????????????"+e.getMessage());
        }
        return productRequest;
    }

    /**
     *
     * @param productId ??????id
     * @param type ?????????recycle??????????????? delete??????????????????
     * @return Boolean
     */
    @Override
    public Boolean deleteProduct(Integer productId, String type) {
        StoreProduct product = getById(productId);
        if (ObjectUtil.isNull(product)) {
            throw new CrmebException("???????????????");
        }
        if (StrUtil.isNotBlank(type) && "recycle".equals(type) && product.getIsDel()) {
            throw new CrmebException("????????????????????????");
        }

        LambdaUpdateWrapper<StoreProduct> lambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        if (StrUtil.isNotBlank(type) && "delete".equals(type)) {
            // ????????????????????????(????????????????????????)
            isExistActivity(productId);

            lambdaUpdateWrapper.eq(StoreProduct::getId, productId);
            lambdaUpdateWrapper.set(StoreProduct::getIsDel, true);
            return update(lambdaUpdateWrapper);
        }
        lambdaUpdateWrapper.eq(StoreProduct::getId, productId);
        lambdaUpdateWrapper.set(StoreProduct::getIsRecycle, true);
        return update(lambdaUpdateWrapper);
    }

    /**
     * ????????????????????????(????????????????????????)
     * @param productId
     */
    private void isExistActivity(Integer productId) {
        Boolean existActivity = false;
        // ??????????????????
        existActivity = storeSeckillService.isExistActivity(productId);
        if (existActivity) {
            throw new CrmebException("????????????????????????????????????????????????????????????");
        }
        // ??????????????????
        existActivity = storeBargainService.isExistActivity(productId);
        if (existActivity) {
            throw new CrmebException("????????????????????????????????????????????????????????????");
        }
        // ??????????????????
        existActivity = storeCombinationService.isExistActivity(productId);
        if (existActivity) {
            throw new CrmebException("????????????????????????????????????????????????????????????");
        }
    }

    /**
     * ????????????????????????
     * @param productId ??????id
     * @return ????????????
     */
    @Override
    public Boolean reStoreProduct(Integer productId) {
        LambdaUpdateWrapper<StoreProduct> lambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        lambdaUpdateWrapper.eq(StoreProduct::getId, productId);
        lambdaUpdateWrapper.set(StoreProduct::getIsRecycle, false);
        return update(lambdaUpdateWrapper);
    }

    ///////////////////////////////////////////???????????????

    /**
     * ????????????????????????
     * @param storeProductStockRequest ??????????????????
     * @return ????????????
     */
    @Override
    public boolean doProductStock(StoreProductStockRequest storeProductStockRequest) {
        // ????????????????????????
        StoreProduct existProduct = getById(storeProductStockRequest.getProductId());
        List<StoreProductAttrValue> existAttr =
                storeProductAttrValueService.getListByProductIdAndAttrId(
                        storeProductStockRequest.getProductId(),
                        storeProductStockRequest.getAttrId().toString(),
                        storeProductStockRequest.getType());
        if (null == existProduct || null == existAttr) { // ???????????????
            logger.info("??????????????????????????????????????????"+JSON.toJSONString(storeProductStockRequest));
            return true;
        }

        // ??????????????????/?????? ?????????
        boolean isPlus = storeProductStockRequest.getOperationType().equals("add");
        int productStock = isPlus ? existProduct.getStock() + storeProductStockRequest.getNum() : existProduct.getStock() - storeProductStockRequest.getNum();
        existProduct.setStock(productStock);
        existProduct.setSales(existProduct.getSales() - storeProductStockRequest.getNum());
        updateById(existProduct);

        // ??????sku??????
        for (StoreProductAttrValue attrValue : existAttr) {
            int productAttrStock = isPlus ? attrValue.getStock() + storeProductStockRequest.getNum() : attrValue.getStock() - storeProductStockRequest.getNum();
            attrValue.setStock(productAttrStock);
            attrValue.setSales(attrValue.getSales()-storeProductStockRequest.getNum());
            storeProductAttrValueService.updateById(attrValue);
        }
        return true;
    }

    /**
     * ????????????????????????
     * @return copyType ???????????????1????????????
     *         copyNum ????????????(????????????????????????)
     */
    @Override
    public MyRecord copyConfig() {
        String copyType = systemConfigService.getValueByKey("system_product_copy_type");
        if (StrUtil.isBlank(copyType)) {
            throw new CrmebException("??????????????????????????????");
        }
        int copyNum = 0;
        if (copyType.equals("1")) {// ?????????
            JSONObject info = onePassService.info();
            copyNum = Optional.ofNullable(info.getJSONObject("copy").getInteger("num")).orElse(0);
        }
        MyRecord record = new MyRecord();
        record.set("copyType", copyType);
        record.set("copyNum", copyNum);
        return record;
    }

    /**
     * ??????????????????
     * @param url ????????????
     * @return MyRecord
     */
    @Override
    public MyRecord copyProduct(String url) {
        JSONObject jsonObject = onePassService.copyGoods(url);
        StoreProductRequest storeProductRequest = ProductUtils.onePassCopyTransition(jsonObject);
        MyRecord record = new MyRecord();
        return record.set("info", storeProductRequest);
    }

    /**
     * ??????/????????????
     * @param id ??????id
     * @param num ??????
     * @param type ?????????add????????????sub?????????
     */
    @Override
    public Boolean operationStock(Integer id, Integer num, String type) {
        UpdateWrapper<StoreProduct> updateWrapper = new UpdateWrapper<>();
        if (type.equals("add")) {
            updateWrapper.setSql(StrUtil.format("stock = stock + {}", num));
            updateWrapper.setSql(StrUtil.format("sales = sales - {}", num));
        }
        if (type.equals("sub")) {
            updateWrapper.setSql(StrUtil.format("stock = stock - {}", num));
            updateWrapper.setSql(StrUtil.format("sales = sales + {}", num));
            // ??????????????????????????????????????????
            updateWrapper.last(StrUtil.format(" and (stock - {} >= 0)", num));
        }
        updateWrapper.eq("id", id);
        boolean update = update(updateWrapper);
        if (!update) {
            throw new CrmebException("??????????????????????????????,??????id = " + id);
        }
        return update;
    }

    /**
     * ??????
     * @param id ??????id
     */
    @Override
    public Boolean offShelf(Integer id) {
        StoreProduct storeProduct = getById(id);
        if (ObjectUtil.isNull(storeProduct)) {
            throw new CrmebException("???????????????");
        }
        if (!storeProduct.getIsShow()) {
            return true;
        }

        storeProduct.setIsShow(false);
        Boolean execute = transactionTemplate.execute(e -> {
            dao.updateById(storeProduct);
            storeCartService.productStatusNotEnable(id);
            // ????????????????????????????????????
            storeProductRelationService.deleteByProId(storeProduct.getId());
            return Boolean.TRUE;
        });

        return execute;
    }

    /**
     * ??????
     * @param id ??????id
     * @return Boolean
     */
    @Override
    public Boolean putOnShelf(Integer id) {
        StoreProduct storeProduct = getById(id);
        if (ObjectUtil.isNull(storeProduct)) {
            throw new CrmebException("???????????????");
        }
        if (storeProduct.getIsShow()) {
            return true;
        }

        // ????????????skuid
        StoreProductAttrValue tempSku = new StoreProductAttrValue();
        tempSku.setProductId(id);
        tempSku.setType(Constants.PRODUCT_TYPE_NORMAL);
        List<StoreProductAttrValue> skuList = storeProductAttrValueService.getByEntity(tempSku);
        List<Integer> skuIdList = skuList.stream().map(StoreProductAttrValue::getId).collect(Collectors.toList());

        storeProduct.setIsShow(true);
        Boolean execute = transactionTemplate.execute(e -> {
            dao.updateById(storeProduct);
            storeCartService.productStatusNoEnable(skuIdList);
            return Boolean.TRUE;
        });
        return execute;
    }

    /**
     * ??????????????????
     * @param type ?????? ???1 ???????????? 2 ???????????? 3???????????? 4???????????????
     * @param pageParamRequest ????????????
     * @return CommonPage
     */
    @Override
    public List<StoreProduct> getIndexProduct(Integer type, PageParamRequest pageParamRequest) {
        PageHelper.startPage(pageParamRequest.getPage(), pageParamRequest.getLimit());
        LambdaQueryWrapper<StoreProduct> lambdaQueryWrapper = Wrappers.lambdaQuery();
        lambdaQueryWrapper.select(StoreProduct::getId, StoreProduct::getImage, StoreProduct::getStoreName,
                StoreProduct::getPrice, StoreProduct::getOtPrice, StoreProduct::getActivity);
        switch (type) {
            case Constants.INDEX_RECOMMEND_BANNER: //????????????
                lambdaQueryWrapper.eq(StoreProduct::getIsBest, true);
                break;
            case Constants.INDEX_HOT_BANNER: //????????????
                lambdaQueryWrapper.eq(StoreProduct::getIsHot, true);
                break;
            case Constants.INDEX_NEW_BANNER: //????????????
                lambdaQueryWrapper.eq(StoreProduct::getIsNew, true);
                break;
            case Constants.INDEX_BENEFIT_BANNER: //????????????
                lambdaQueryWrapper.eq(StoreProduct::getIsBenefit, true);
                break;
            case Constants.INDEX_GOOD_BANNER: // ????????????
                lambdaQueryWrapper.eq(StoreProduct::getIsGood, true);
                break;
        }

        lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
        lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
        lambdaQueryWrapper.gt(StoreProduct::getStock, 0);
        lambdaQueryWrapper.eq(StoreProduct::getIsShow, true);

        lambdaQueryWrapper.orderByDesc(StoreProduct::getSort);
        lambdaQueryWrapper.orderByDesc(StoreProduct::getId);
        return dao.selectList(lambdaQueryWrapper);
    }

    /**
     * ???????????????????????????
     * @param request ????????????
     * @param pageRequest ????????????
     * @return List
     */
    @Override
    public List<StoreProduct> findH5List(ProductRequest request, PageParamRequest pageRequest) {

        LambdaQueryWrapper<StoreProduct> lqw = Wrappers.lambdaQuery();
        // id?????????????????????????????????????????????
        lqw.select(StoreProduct::getId, StoreProduct::getStoreName, StoreProduct::getImage, StoreProduct::getPrice,
                StoreProduct::getActivity, StoreProduct::getSales, StoreProduct::getFicti, StoreProduct::getUnitName,
                StoreProduct::getFlatPattern, StoreProduct::getStock);

        lqw.eq(StoreProduct::getIsRecycle, false);
        lqw.eq(StoreProduct::getIsDel, false);
        lqw.eq(StoreProduct::getMerId, false);
        lqw.gt(StoreProduct::getStock, 0);
        lqw.eq(StoreProduct::getIsShow, true);

        if (ObjectUtil.isNotNull(request.getCid()) && request.getCid() > 0) {
            //?????????????????????????????????
            List<Category> childVoListByPid = categoryService.getChildVoListByPid(request.getCid());
            List<Integer> categoryIdList = childVoListByPid.stream().map(Category::getId).collect(Collectors.toList());
            categoryIdList.add(request.getCid());
            lqw.apply(CrmebUtil.getFindInSetSql("cate_id", (ArrayList<Integer>) categoryIdList));
        }

        if (StrUtil.isNotBlank(request.getKeyword())) {
            if (CrmebUtil.isString2Num(request.getKeyword())) {
                Integer productId = Integer.valueOf(request.getKeyword());
                lqw.like(StoreProduct::getId, productId);
            } else {
                lqw.like(StoreProduct::getStoreName, request.getKeyword());
            }
        }

        // ????????????
        if (StrUtil.isNotBlank(request.getSalesOrder())) {
            if (request.getSalesOrder().equals(Constants.SORT_DESC)) {
                lqw.last(" order by (sales + ficti) desc, sort desc, id desc");
            } else {
                lqw.last(" order by (sales + ficti) asc, sort asc, id asc");
            }
        } else {
            if (StrUtil.isNotBlank(request.getPriceOrder())) {
                if (request.getPriceOrder().equals(Constants.SORT_DESC)) {
                    lqw.orderByDesc(StoreProduct::getPrice);
                } else {
                    lqw.orderByAsc(StoreProduct::getPrice);
                }
            }

            lqw.orderByDesc(StoreProduct::getSort);
            lqw.orderByDesc(StoreProduct::getId);
        }
        PageHelper.startPage(pageRequest.getPage(), pageRequest.getLimit());
        return dao.selectList(lqw);
    }

    /**
     * ???????????????????????????
     * @param id ??????id
     * @return StoreProduct
     */
    @Override
    public StoreProduct getH5Detail(Integer id) {
        LambdaQueryWrapper<StoreProduct> lqw = Wrappers.lambdaQuery();
        lqw.select(StoreProduct::getId, StoreProduct::getImage, StoreProduct::getStoreName, StoreProduct::getSliderImage,
                StoreProduct::getOtPrice, StoreProduct::getStock, StoreProduct::getSales, StoreProduct::getPrice, StoreProduct::getActivity,
                StoreProduct::getFicti, StoreProduct::getIsSub, StoreProduct::getStoreInfo, StoreProduct::getBrowse, StoreProduct::getUnitName);
        lqw.eq(StoreProduct::getId, id);
        lqw.eq(StoreProduct::getIsRecycle, false);
        lqw.eq(StoreProduct::getIsDel, false);
        lqw.eq(StoreProduct::getIsShow, true);
        StoreProduct storeProduct = dao.selectOne(lqw);
        if (ObjectUtil.isNull(storeProduct)) {
            throw new CrmebException(StrUtil.format("??????????????????{}?????????", id));
        }

        StoreProductDescription sd = storeProductDescriptionService.getOne(
                new LambdaQueryWrapper<StoreProductDescription>()
                        .eq(StoreProductDescription::getProductId, storeProduct.getId())
                        .eq(StoreProductDescription::getType, Constants.PRODUCT_TYPE_NORMAL));
        if (ObjectUtil.isNotNull(sd)) {
            storeProduct.setContent(StrUtil.isBlank(sd.getDescription()) ? "" : sd.getDescription());
        }
        return storeProduct;
    }

    /**
     * ???????????????????????????
     * @param productId ????????????
     * @return StoreProduct
     */
    @Override
    public StoreProduct getCartByProId(Integer productId) {
        LambdaQueryWrapper<StoreProduct> lqw = Wrappers.lambdaQuery();
        lqw.select(StoreProduct::getId, StoreProduct::getImage, StoreProduct::getStoreName);
        lqw.eq(StoreProduct::getId, productId);
        return dao.selectOne(lqw);
    }

    /**
     * ????????????????????????????????????
     * @param date ?????????yyyy-MM-dd??????
     * @return Integer
     */
    @Override
    public Integer getNewProductByDate(String date) {
        LambdaQueryWrapper<StoreProduct> lqw = Wrappers.lambdaQuery();
        lqw.select(StoreProduct::getId);
        lqw.eq(StoreProduct::getIsDel, 0);
        lqw.apply("date_format(add_time, '%Y-%m-%d') = {0}", date);
        return dao.selectCount(lqw);
    }

    /**
     * ??????????????????????????????
     * @return List<StoreProduct>
     */
    @Override
    public List<StoreProduct> findAllProductByNotDelte() {
        LambdaQueryWrapper<StoreProduct> lqw = Wrappers.lambdaQuery();
        lqw.select(StoreProduct::getId);
        lqw.eq(StoreProduct::getIsDel, 0);
        return dao.selectList(lqw);
    }

    /**
     * ????????????????????????
     * @param productName ????????????
     * @return List
     */
    @Override
    public List<StoreProduct> likeProductName(String productName) {
        LambdaQueryWrapper<StoreProduct> lqw = Wrappers.lambdaQuery();
        lqw.select(StoreProduct::getId);
        lqw.like(StoreProduct::getStoreName, productName);
        lqw.eq(StoreProduct::getIsDel, 0);
        return dao.selectList(lqw);
    }

    /**
     * ??????????????????
     * @return Integer
     */
    @Override
    public Integer getVigilanceInventoryNum() {
        Integer stock = Integer.parseInt(systemConfigService.getValueByKey("store_stock"));
        LambdaQueryWrapper<StoreProduct> lambdaQueryWrapper = Wrappers.lambdaQuery();
        lambdaQueryWrapper.le(StoreProduct::getStock, stock);
        lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
        lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
        return dao.selectCount(lambdaQueryWrapper);
    }

    /**
     * ?????????????????????????????????
     * @return Integer
     */
    @Override
    public Integer getOnSaleNum() {
        LambdaQueryWrapper<StoreProduct> lambdaQueryWrapper = Wrappers.lambdaQuery();
        lambdaQueryWrapper.eq(StoreProduct::getIsShow, true);
        lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
        lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
        return dao.selectCount(lambdaQueryWrapper);
    }

    /**
     * ?????????????????????????????????
     * @return Integer
     */
    @Override
    public Integer getNotSaleNum() {
        LambdaQueryWrapper<StoreProduct> lambdaQueryWrapper = Wrappers.lambdaQuery();
        lambdaQueryWrapper.eq(StoreProduct::getIsShow, false);
        lambdaQueryWrapper.eq(StoreProduct::getIsRecycle, false);
        lambdaQueryWrapper.eq(StoreProduct::getIsDel, false);
        return dao.selectCount(lambdaQueryWrapper);
    }

    /**
     * ?????????????????????
     * 1.   3??????????????????????????????
     * 2.   TOP10
     * @return List
     */
    @Override
    public List<StoreProduct> getLeaderboard() {
        QueryWrapper<StoreProduct> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("is_show", true);
        queryWrapper.eq("is_recycle", false);
        queryWrapper.eq("is_del", false);
        queryWrapper.last("limit 10");
        Integer count = dao.selectCount(queryWrapper);
        if (count < 4) {
            return CollUtil.newArrayList();
        }
        queryWrapper.select("id", "store_name", "image", "price", "ot_price", "(sales + ficti) as sales");
        queryWrapper.orderByDesc("sales");
        return dao.selectList(queryWrapper);
    }

}

