package com.wf.gts.core.handler;
import java.util.concurrent.CompletableFuture;

import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.wf.gts.common.beans.TransItem;
import com.wf.gts.common.enums.TransRoleEnum;
import com.wf.gts.common.enums.TransStatusEnum;
import com.wf.gts.common.utils.IdWorkerUtils;
import com.wf.gts.core.bean.GtsTransInfo;
import com.wf.gts.core.concurrent.BlockTask;
import com.wf.gts.core.concurrent.BlockTaskHelper;
import com.wf.gts.core.constant.Constant;
import com.wf.gts.core.service.GtsMessageService;
import com.wf.gts.core.util.ThreadPoolManager;

/**
 * 分布式事务运参与者
 */
@Component
public class JoinGtsTransHandler implements GtsTransHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JoinGtsTransHandler.class);
    
    private final GtsMessageService gtsMessageService;
    private final PlatformTransactionManager platformTransactionManager;

    @Autowired
    public JoinGtsTransHandler(PlatformTransactionManager platformTransactionManager, GtsMessageService gtsMessageService) {
        this.platformTransactionManager = platformTransactionManager;
        this.gtsMessageService = gtsMessageService;
    }

    
    
    @Override
    public Object handler(ProceedingJoinPoint point, GtsTransInfo info) throws Throwable {
      
        LOGGER.info("分布式事务参与方，开始执行,事务组id:{},方法名称:{},服务名:{}",
            info.getTxGroupId(),info.getInvocation().getMethod(),info.getInvocation().getClass().toString());
        
        final String taskKey = IdWorkerUtils.getInstance().createTaskKey();
        final BlockTask task = BlockTaskHelper.getInstance().getTask(taskKey);
        
        ThreadPoolManager.getInstance().addExecuteTask(() -> {
          
            final String waitKey = IdWorkerUtils.getInstance().createTaskKey();
            final BlockTask waitTask = BlockTaskHelper.getInstance().getTask(waitKey);
            TransactionStatus transactionStatus=startTransaction();
            try {
                //添加事务组信息
                if (addTxTransactionGroup(waitKey, info)) {
                    final Object res = point.proceed();
                    //设置返回数据，并唤醒之前等待的主线程
                    task.setAsyncCall(objects -> res);
                    task.signal();
                    try {
                        //等待gtsManage通知
                        long nana=waitTask.await(info.getTxTransaction()
                            .socketTimeout()*Constant.CONSTANT_INT_THOUSAND*Constant.CONSTANT_INT_THOUSAND);
                        
                        if(nana<=0){
                          findTransactionGroupStatus(info, waitTask);
                        }
                        commitOrRollback(transactionStatus, info, waitTask, waitKey);
                        
                    } catch (Throwable throwable) {
                        platformTransactionManager.rollback(transactionStatus);
                        LOGGER.error("分布式事务参与方,事务提交异常:{},事务组id:{}",throwable.getMessage(),info.getTxGroupId());
                    } finally {
                        BlockTaskHelper.getInstance().removeByKey(waitKey);
                    }
  
                } else {
                    platformTransactionManager.rollback(transactionStatus);
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                platformTransactionManager.rollback(transactionStatus);
                task.setAsyncCall(objects -> {
                    throw throwable;
                });
                task.signal();
            }
        });
        task.await();
        LOGGER.info("参与分布式模块执行完毕,事务组id:{},方法名称:{},服务名:{}",
            info.getTxGroupId(),info.getInvocation().getMethod(),info.getInvocation().getClass().toString());
        try {
            return task.getAsyncCall().callBack();
        } finally {
            BlockTaskHelper.getInstance().removeByKey(task.getKey());
        }
    }
    
    /**
     * 功能描述: 提交或者超时回滚
     * @author: chenjy
     * @date: 2017年9月18日 下午1:28:56 
     * @param transactionStatus
     * @param info
     * @param waitTask
     * @param waitKey
     * @throws Throwable 
     */
    private void commitOrRollback(TransactionStatus transactionStatus,GtsTransInfo info,BlockTask waitTask,String waitKey) throws Throwable{
      final Integer status = (Integer) waitTask.getAsyncCall().callBack();
      if (TransStatusEnum.COMMIT.getCode() == status) {
          //提交事务
          platformTransactionManager.commit(transactionStatus);
          //通知tm完成事务
          CompletableFuture.runAsync(() ->
                      gtsMessageService 
                          .AsyncCompleteCommitTxTransaction(info.getTxGroupId(), waitKey,
                                  TransStatusEnum.COMMIT.getCode()));

      } else if (TransStatusEnum.ROLLBACK.getCode()== status) {
          //如果超时了，就回滚当前事务
          platformTransactionManager.rollback(transactionStatus);
          //通知tm 自身事务需要回滚,不能提交
          CompletableFuture.runAsync(() ->
                      gtsMessageService
                          .AsyncCompleteCommitTxTransaction(info.getTxGroupId(), waitKey,
                                  TransStatusEnum.ROLLBACK.getCode()));
      }
      
    }
    
    
    /**
     * 功能描述: 查找事务组状态
     * @author: chenjy
     * @date: 2017年9月18日 下午1:24:39 
     * @param info
     * @param waitTask
     * @throws Throwable 
     */
    private void  findTransactionGroupStatus(GtsTransInfo info,BlockTask waitTask) throws Throwable{
        //如果获取通知超时了，那么就去获取事务组的状态
        final int transactionGroupStatus = gtsMessageService.findTransactionGroupStatus(info.getTxGroupId(),info.getTxTransaction().socketTimeout());
        
        if (TransStatusEnum.PRE_COMMIT.getCode() == transactionGroupStatus ||
                TransStatusEnum.COMMIT.getCode() == transactionGroupStatus) {
            LOGGER.info("事务组id：{}，自动超时，获取事务组状态为提交，进行提交!", info.getTxGroupId());
            waitTask.setAsyncCall(objects -> TransStatusEnum.COMMIT.getCode());
        } else {
            LOGGER.info("事务组id：{}，自动超时进行回滚!", info.getTxGroupId());
            waitTask.setAsyncCall(objects -> TransStatusEnum.ROLLBACK.getCode());
        }
        LOGGER.error("从redis查询事务状态为:{}", transactionGroupStatus);
    }

    
    /**
     * 功能描述: 开启事务
     * @author: chenjy
     * @date: 2017年9月15日 下午5:48:29 
     * @return
     */
    private TransactionStatus startTransaction(){
      DefaultTransactionDefinition def = new DefaultTransactionDefinition();
      def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      TransactionStatus transactionStatus = platformTransactionManager.getTransaction(def);
      return transactionStatus;
    }
    
    
    /**
     * 功能描述: 添加事务组
     * @author: chenjy
     * @date:   2017年9月15日 下午5:48:49 
     * @param   waitKey
     * @param   info
     * @return
     * @throws Throwable 
     */
    private boolean addTxTransactionGroup(String waitKey,GtsTransInfo info) throws Throwable{
      TransItem item = new TransItem();
      item.setTaskKey(waitKey);
      item.setTransId(IdWorkerUtils.getInstance().createUUID());
      item.setStatus(TransStatusEnum.BEGIN.getCode());//开始事务
      item.setRole(TransRoleEnum.ACTOR.getCode());//参与者
      item.setTxGroupId(info.getTxGroupId());
      return gtsMessageService.addTxTransaction(info.getTxGroupId(), item,info.getTxTransaction().socketTimeout());
    }


}
