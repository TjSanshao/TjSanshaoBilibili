package top.tjsanshao.bilibili.action;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.tjsanshao.bilibili.api.APIList;
import top.tjsanshao.bilibili.api.OftenAPI;
import top.tjsanshao.bilibili.constant.BilibiliResponseConstant;
import top.tjsanshao.bilibili.constant.BilibiliTypeConstant;
import top.tjsanshao.bilibili.current.ActionResult;
import top.tjsanshao.bilibili.current.CurrentUser;
import top.tjsanshao.bilibili.http.BilibiliRequestClient;
import top.tjsanshao.bilibili.login.PassCheck;
import top.tjsanshao.bilibili.util.TjSanshaoDateUtil;

import javax.annotation.Resource;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;

/**
 * charge for me
 *
 * @author TjSanshao
 * @date 2020-12-29 17:00
 */
@Slf4j
@Component
public class ChargeMe implements Action {
    @Resource
    private OftenAPI often;

    @Resource
    private BilibiliRequestClient client;
    @Resource
    private PassCheck passCheck;

    @Override
    public String actionName() {
        return "自动充电";
    }

    @Override
    public String resultKey() {
        return "ChargeMe";
    }

    @Override
    public void act() {
        ActionResult ar = new ActionResult();
        ar.setAction(this.actionName());

        if (!CurrentUser.chargeMe) {
            log.warn("自动充电功能未开启！");
            ar.setActionResultCode(-9999);
            ar.setActionResultMessage("啊？原来是没开自动充电...");
            ar.setActionFinishedTime(TjSanshaoDateUtil.now());
            CurrentUser.actionResult.put(this.resultKey(), ar);
            return;
        }

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"));
        int date = calendar.get(Calendar.DATE);
        int vipType = 0;
        if (CurrentUser.userInfo.getVipStatus() == BilibiliTypeConstant.VIP_EFFECT) {
            // 当vipStatus == 1时，vip才有效
            vipType = CurrentUser.userInfo.getVipType();
            if (date == 1) {
                // 每月1号领大会员福利
                often.vipPrivilege(BilibiliTypeConstant.B_COIN);
                often.vipPrivilege(BilibiliTypeConstant.VIP_REWARD);
            }
        }
        if (vipType == BilibiliTypeConstant.NORMAL_VIP || vipType == BilibiliTypeConstant.MONTH_VIP) {
            // 普通会员和月度会员不赠送B币券，无法充电
            log.warn("不是年度大会员！");
            ar.setActionResultCode(-9999);
            ar.setActionResultMessage("啊？我竟然不是年度大会员？！？！");
            ar.setActionFinishedTime(TjSanshaoDateUtil.now());
            CurrentUser.actionResult.put(this.resultKey(), ar);
            return;
        }

        // 被充电用户的userID
        String userId = passCheck.getUserId();
        // B币券余额
        int couponBalance = 0;

        if (Objects.nonNull(CurrentUser.userInfo)) {
            couponBalance = CurrentUser.userInfo.getWallet().getCoupon_balance();
        } else {
            JsonObject response = client.get(APIList.CHARGE_QUERY + "?mid=" + userId);
            if (response.get(BilibiliResponseConstant.CODE).getAsInt() == BilibiliResponseConstant.CODE_SUCCESS) {
                couponBalance = response.getAsJsonObject(BilibiliResponseConstant.DATA).getAsJsonObject(BilibiliResponseConstant.BP_WALLET).get(BilibiliResponseConstant.COUPON_BALANCE).getAsInt();
            }
        }

        if (couponBalance >= 2 && date == 28) {
            // B币券大于2且月底时，充电
            String postBody = "bp_num=" + couponBalance
                    + "&is_bp_remains_prior=true"
                    + "&up_mid=" + userId
                    + "&otype=up"
                    + "&oid=" + userId
                    + "&csrf=" + passCheck.getBiliJct();
            JsonObject response = client.post(APIList.CHARGE, postBody);
            if (response.get(BilibiliResponseConstant.CODE).getAsInt() == BilibiliResponseConstant.CODE_SUCCESS) {
                // 充电请求成功成功
                JsonObject data = response.getAsJsonObject(BilibiliResponseConstant.DATA);
                int status = data.get(BilibiliResponseConstant.STATUS).getAsInt();
                if (status == BilibiliTypeConstant.CHARGE_SUCCESS) {
                    // 充电成功
                    log.info("月底啦，给自己充电成功啦！送的B币券没有浪费哦！");
                    log.info("本次充值使用了：{}个B币券", couponBalance);
                    String order = data.get(BilibiliResponseConstant.ORDER_NO).getAsString();
                    this.chargeComment(order);

                    ar.setActionResultCode(0);
                    ar.setBilibiliCode(0);
                    String arMsg = String.format("月底啦，给自己充电成功啦！白送的B币券没有浪费哦！这次充电用掉了%d个B币券", couponBalance);
                    ar.setActionResultMessage(arMsg);
                    ar.setActionFinishedTime(TjSanshaoDateUtil.now());
                } else {
                    log.warn("糟糕...充电失败了...原因竟然是这样：{}", response);
                    ar.setActionResultCode(0);
                    ar.setBilibiliCode(0);
                    String arMsg = String.format("糟糕...充电失败了...原因竟然是这样：%s", response.get(BilibiliResponseConstant.MESSAGE).getAsString());
                    ar.setActionResultMessage(arMsg);
                    ar.setActionFinishedTime(TjSanshaoDateUtil.now());
                }
            } else {
                log.warn("糟糕...B站不让我充电...原因竟然是这样：{}", response);
                int code = response.get(BilibiliResponseConstant.CODE).getAsInt();
                ar.setActionResultCode(code);
                ar.setBilibiliCode(code);
                String arMsg = String.format("糟糕...B站不让我充电...原因竟然是这样：%s", response.get(BilibiliResponseConstant.MESSAGE).getAsString());
                ar.setActionResultMessage(arMsg);
                ar.setActionFinishedTime(TjSanshaoDateUtil.now());
            }
        } else {
            log.warn("shit...今天不是28号，不充电...");
            ar.setActionResultCode(-9999);
            ar.setActionResultMessage("shit...今天不是28号，不充电...");
            ar.setActionFinishedTime(TjSanshaoDateUtil.now());
        }
        CurrentUser.actionResult.put(this.resultKey(), ar);
    }

    /**
     * 充电留言
     * @param order 充电订单
     */
    private void chargeComment(String order) {
        String postBody = "order_id=" + order
                + "&message=" + "TjSanshao-Bilibili自动充电"
                + "&csrf=" + passCheck.getBiliJct();
        JsonObject response = client.post(APIList.CHARGE_COMMENT, postBody);
        if (response.get(BilibiliResponseConstant.CODE).getAsInt() == BilibiliResponseConstant.CODE_SUCCESS) {
            log.info("充电留言成功！");
        } else {
            log.warn("充电留言失败！原因：{}", response.get(BilibiliResponseConstant.MESSAGE).getAsString());
        }
    }
}
