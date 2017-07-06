package com.lorne.tx.service.impl;

import com.lorne.core.framework.Constant;
import com.lorne.core.framework.exception.ServiceException;
import com.lorne.core.framework.utils.KidUtils;
import com.lorne.core.framework.utils.task.ConditionUtils;
import com.lorne.core.framework.utils.task.IBack;
import com.lorne.core.framework.utils.task.Task;
import com.lorne.tx.Constants;
import com.lorne.tx.mq.model.Request;
import com.lorne.tx.mq.model.TxGroup;
import com.lorne.tx.mq.service.MQTxManagerService;
import com.lorne.tx.mq.service.NettyService;
import com.lorne.tx.service.TransactionThreadService;
import com.lorne.tx.service.model.ExecuteAwaitTask;
import com.lorne.tx.service.model.ServiceThreadModel;
import net.sf.json.JSONObject;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.concurrent.TimeUnit;

/**
 * Created by lorne on 2017/6/9.
 */
@Service
public class TransactionThreadServiceImpl implements TransactionThreadService {

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private MQTxManagerService txManagerService;

    @Autowired
    private NettyService nettyService;


    private Logger logger = LoggerFactory.getLogger(TransactionThreadServiceImpl.class);

    @Override
    public ServiceThreadModel serviceInThread(boolean signTask, String _groupId, Task task, ProceedingJoinPoint point) {

        String kid = KidUtils.generateShortUuid();
        TxGroup txGroup = txManagerService.addTransactionGroup(_groupId, kid);

        //获取不到模块信息重新连接，本次事务异常返回数据.
        if (txGroup == null) {
            task.setBack(new IBack() {
                @Override
                public Object doing(Object... objects) throws Throwable {
                    throw new ServiceException("添加事务组异常.");
                }
            });
            task.signalTask();
            nettyService.restart();
            return null;
        }

        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        TransactionStatus status = txManager.getTransaction(def);

        Task waitTask = ConditionUtils.getInstance().createTask(kid);

        //发送数据是否成功
        boolean isSend = false;

        //执行是否成功
        boolean executeOk = false;

        try {
            final Object res = point.proceed();
            task.setBack(new IBack() {
                @Override
                public Object doing(Object... objects) throws Throwable {
                    return res;
                }
            });
            //通知TxManager调用成功
            executeOk = true;

        } catch (final Throwable throwable) {
            task.setBack(new IBack() {
                @Override
                public Object doing(Object... objects) throws Throwable {
                    throw throwable;
                }
            });
            //通知TxManager调用失败
            executeOk = false;
        }

        isSend = txManagerService.notifyTransactionInfo(_groupId, kid, executeOk);

        ServiceThreadModel model = new ServiceThreadModel();
        model.setStatus(status);
        model.setWaitTask(waitTask);
        model.setTxGroup(txGroup);
        model.setNotifyOk(isSend);

        return model;

    }


    private void waitSignTask(Task task, ExecuteAwaitTask executeAwaitTask,final ExecuteAwaitTask closeGroupTask) {
        if (executeAwaitTask.getState() == 1) {
             task.signalTask(new IBack() {
                 @Override
                 public Object doing(Object... objs) throws Throwable {
                     closeGroupTask.setState(1);
                     return null;
                 }
             });
        } else {
            try {
                Thread.sleep(3);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            waitSignTask(task, executeAwaitTask,closeGroupTask);
        }
    }

    @Override
    public void serviceWait(boolean signTask,final Task task, final ServiceThreadModel model) {
        Task waitTask = model.getWaitTask();
        final String taskId = waitTask.getKey();
        TransactionStatus status = model.getStatus();

        Constant.scheduledExecutorService.schedule(new Runnable() {
            @Override
            public void run() {
                Task task = ConditionUtils.getInstance().getTask(taskId);
                if (task.getState() == 0) {
                    task.setBack(new IBack() {
                        @Override
                        public Object doing(Object... objects) throws Throwable {
                            return -2;
                        }
                    });
                    logger.info("自定回滚执行");
                    task.signalTask();
                }
            }
        }, model.getTxGroup().getWaitTime(), TimeUnit.SECONDS);

        //主线程先执行
        final ExecuteAwaitTask mainTaskAwait = new ExecuteAwaitTask();

        final ExecuteAwaitTask closeGroupTask = new ExecuteAwaitTask();

        if (model.isNotifyOk() == false || signTask) {
            if (model.isNotifyOk() == false) {
                task.setBack(new IBack() {
                    @Override
                    public Object doing(Object... objects) throws Throwable {
                        throw new ServiceException("修改事务组状态异常.");
                    }
                });
            }
            Constants.threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    waitSignTask(task,mainTaskAwait,closeGroupTask); //执行顺序 2
                }
            });
        }


        if (!signTask) {
            txManagerService.closeTransactionGroup(model.getTxGroup().getGroupId(), closeGroupTask); //执行顺序 3
        }
        logger.info("进入回滚等待.");
        waitTask.awaitTask(new IBack() {
            @Override
            public Object doing(Object... objs) throws Throwable {
                mainTaskAwait.setState(1);// 执行顺序 1
                return null;
            }
        });

        try {
            int state = (Integer) waitTask.getBack().doing();
            logger.info("单元事务（1：提交 0：回滚 -1：事务模块网络异常回滚 -2：事务模块超时异常回滚）:" + state);
            if (state == 1) {
                txManager.commit(status);
            } else {
                txManager.rollback(status);
                if (state == -1) {
                    task.setBack(new IBack() {
                        @Override
                        public Object doing(Object... objs) throws Throwable {
                            throw new Throwable("事务模块网络异常.");
                        }
                    });
                }
                if (state == -2) {
                    task.setBack(new IBack() {
                        @Override
                        public Object doing(Object... objs) throws Throwable {
                            throw new Throwable("事务模块超时异常.");
                        }
                    });
                }
            }
            if (!signTask) {
                task.signalTask();
            }
        } catch (Throwable throwable) {
            txManager.rollback(status);
        } finally {
            waitTask.remove();
        }
    }


}
