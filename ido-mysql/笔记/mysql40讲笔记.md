##极客时间Mysql40讲笔记

          1、Mysql架构
          client层：
          sever层：
          连接器：连接管理 权限验证
          分析器：语法分析 词法分析
          优化器 ：执行计划生成，索引选择
          执行器 ：操作引擎，返回结果
          存储引擎层
          存储引擎 ：存储数据，提供读写接口

          连接器 -- > 分析器--> 优化器-->  执行器 --> 存储层

          2、Mysql的日志

          2.1 redo log日志（属于innodb存储引擎层的日志）
          一个环形的结构，可以有多个文件，write_pos写的位置，checkpoint表示刷盘的位置，write_pos到checkpoint之间是可写的位置
          redo-log可以保证crash-safe  这样的话，意味着redo-log是可以持久化保存的。
          WAL：write ahead logging 先日志后刷盘，因为直接刷盘需要找到目标数据，慢呗，内存先更新再 异步刷盘，可以保证写的性能，读也不影响

          2.2 bin log（server层日志 其他存储引擎也有）
          binlog的三种模式【binlog是一直追加的，文件达到一定大小后，新生成文件】
          row：每一行变化都有记录，副本文件会比较大
          statement ：记录的是每个sql，slave有可能因为索引选择的不同导致主从主句不一致 文件小
          mix：mysql觉得同步数据会有问题的时候就用row的方式，否则用statement的方式

          2.3 mysql参数配置
          innodb_flush_log_at_trx_commit = 1 每次事务redo log都会刷盘
          sync_binlog= 1 每次事务binlog都刷盘


          3、隔离性
          SQL标准的事务隔离级别包括：读未提交，读提交，可重复读，串行化
           mysql> show variables like 'transaction_isolation';
          mysql的MVCC到底是什么东西？ 多版本控制协议，用来确保读提交和读可重复读的一个机制。

          每个事物有一个唯一的事务id：transaction_id  事务开始的时候向innodb的事务系统申请的，且按照严格的顺序递增。

          一行数据-->row trx_id 都有事务id


          4、索引
          hash表：类似一个map的结构，数组+链表，比较适合按照字段的等值查询。
          B+树：二叉搜索树的演变--> N叉的一个树

          主键索引（叶子节点存的是整行数据）--> 聚簇索引
          普通索引（cloumn + id的存储格式） 回表查询主键索引树，查询到具体的数据 -->二级索引
          覆盖索引  查询的字段就是索引字段(最多再加上主键)，索引结构可以直接返回给用户，不需要回表，这种就叫覆盖索引

          为什么表需要有一个自增的id？
          1、如果不是连续的，那么insert数据的时候，构建主键索引树的时候，
          会导致页分裂、成本太大了。自增的id 那么空间都是连续的，不会有这个问题，无非就是其他的二级索引会有分裂的情况。
          2、二级索引 占用的空间也可以小一点

          有没有场景是业务字段直接做主键的？
          1、只有一个索引
          2、该索引是唯一索引
          那么直接把这个索引字段当成主键字段 可以避免回表


          最左前缀原则：
          索引的结构 比如 （a，b）的索引

          索引下推
          select * from table where a like 'name%' and b = 10;
          5.6之前找到name的最左边那条记录后，从小到大一个个回表 查询判断
          5.6之后，索引下推，直接在索引层判断 b是否等于10  不等于 直接过滤，减少回表次数


          5、Mysql的锁
          MySQL的锁大致可以分为 全局锁  表锁 行锁 三类

          全局锁：对整个数据库实例加锁   加锁方式：Flush tables with read lock (FTWRL)

          表级锁：MySQL里面表级别的锁有两种：一种是表锁，一种是元数据锁（meta data lock，MDL)。

          lock table  粒度太大了，一般不建议做，
          举例：线程A lock table t1 read,t2 write，其他线程写t1 读写t2会被阻塞，同时A线程在执行unlock tables之前，也只能执行读t1，读写t2的操作。

          MDL(metaData lock) 不需要显示的使用，访问一个表的时候会自动带上。5.5版本引入
          1、对一个表做CRUD的时候     加MDL读锁
          2、要做DDL操作的时候    加MDL写锁

          读锁之间共享
          写写互斥，读写互斥。
          比如 现在正在执行 select * from test where id  = 1;（已经获取DML读锁了）
          这个时候要alter table test add column b varchar(20) COMMENT '新增字段备注' 需要获取DML写锁，这个时候会阻塞 直到上一个select结束


          Online DDL的过程是这样的：
          1. 拿MDL写锁
          2. 降级成MDL读锁
          3. 真正做DDL----->>>
          4. 升级成MDL写锁
          5. 释放MDL锁
          1、2、4、5如果没有锁冲突，执行时间非常短。第3步占用了DDL绝大部分时间，这期间这个表可以正常读写数据，是因此称为“online ”

          我们文中的例子，是在第一步就堵住了

          行锁：并不是所有的存储引擎都支持行锁的，InnoDB支持行锁

          线程A和线程B两个同时执行sql
          begin;
          update test set k = k + 1 where id = 1;
          commit;
          会发生什么样的现象？ 其中一个线程必须执行commit后，另外一个线程才能update结束，否则一直阻塞着 等待行锁。
          在InnoDB事务中，行锁是在需要的时候才加上的，但并不是不需要了就立刻释放，而是要等到事务结束时才释放。这个就是两阶段锁协议。
          如果在一个事务中，需要锁住多个行，尽量把可能造成锁冲突的行 放在最后面，减少行锁的时间。

          死锁和死锁检测
          A线程
          begin;
          update t set k = k+ 1 where id =1;
          update t set k = k+ 1 where id =2;
          commit;

          B线程
          begin;
          update t set k = k+ 1 where id =2;
          update t set k = k+ 1 where id =1;
          commit;

          线程A执行到 where id= 1,线程B执行到where id= 2 完了，走不下去了，这样就产生了相互等待，死锁了。

          解决方案：
          1、等待超时，innodb_lock_wait_timeout，超过这个时间 线程就退出了 执行rollbak，其他线程可以继续走下去
          2、死锁检查，innodb_deadlock_detect=on  发现死锁后，主动回滚死锁链条中的一个事务，让其他事务可以继续执行

          使用等待超时的方法，时间太长，业务不能接受，时间太短，不好控制，万一是简单的锁等待呢？
          所以正常情况下我们采用第二种策略：主动死锁检查，mysql默认也是开着死锁检查的。
          但是死锁检查也是有额外的开销的，比如我被锁住的时候，我还要判断下是不是别人也被锁住了，如此循环，最后判断是否出现了循环等待的情况，
          但是如果并发量很高，100个线程都在这里处理，咋办？？控制并发度（数据库服务端，自己改源码咯，比如同一行数据的并发度）

          6、普通索引和唯一索引的区别
          查询区别 select * from tableA where k = 100
          普通索引：走k的索引，查到100这个数据后，还会往后取一个数据，直到不是100为止
          唯一索引，走k的唯一索引，找到k=100后回表直接返回
          查询来说，两者差距微乎其微

          更新操作
          数据页在内存
          普通索引，找到对应的位置 插入就好了
          唯一索引，找到对应的位置，判断是否有重复，没有重复的插入

          数据页不在内存
          普通索引，写channel buffer（异步merge 或者查询merge）
          唯一索引，加载数据页，判断是否重复，写入操作


          IO的性能往往是最慢的，我们希望尽量少的IO次数，channel buffer希望这个数据页有更多的数据改变 然后merge
          这样带来的收益是最大的，如果一个更新操作，随后立马伴随着一个查询操作（触发merge），那么IO次数并没有
          减少，反而带来了channel buffer的维护。

          channel buffer 和 redo log有啥区别？
          思考：

          7、为什么Mysql会选错索引
          优化器是怎么选索引的？
          扫描行数，临时表，是否排序等维度考虑

          在MySQL中，有两种存储索引统计的方式，可以通过设置参数innodb_stats_persistent的值来选择：
          设置为on的时候，表示统计信息会持久化存储。这时，默认的N是20，M是10。
          设置为off的时候，表示统计信息只存储在内存中。这时，默认的N是8，M是16。

          innodb会选择N个数据页，统计页面上的不同值，得到一个平均值，然后乘以索引的页面数，就得到了索引的基数。 当数据持续更新 超过 1/M的时候  索引基数需要重新统计

          统计信息不对的情况 修正：analyze table t

          选错了我们如何处理
          1、强制走索引 force index（不美观，且索引名字换了就凉凉了，其他数据库不一定支持）
          select * from table force index（index_name） where xxx = 10;
          2、修改sql 让它选择我们期望的索引
          3、删除老索引，建一个新的索引

          8、如何给字符串加索引
          直接创建完整索引，这样可能比较占用空间；
          创建前缀索引，节省空间，但会增加查询扫描次数，并且不能使用覆盖索引；
          倒序存储，再创建前缀索引，用于绕过字符串本身前缀的区分度不够的问题；
          创建hash字段索引，查询性能稳定，有额外的存储和计算消耗，跟第三种方式一样，都不支持范围扫描。

          9、为什么MySql会抖一下
          写redo log 就直接返回给客户端了

          内存数据页 和 磁盘数据不一致的情况 数据页称为脏页
          当redo log刷到磁盘了（flush操作）,内存数据页和磁盘数据一致的情况，数据页称为干净页

          mysql抖的那一些可能是在flush操作
          什么时候会flush呢？
          1、redo log写满了  写不下了 【尽量避免， 业务写不进来了】
          2、内存满了，需要新的数据页，这个时候需要把脏页的数据 flush到磁盘【一个查询需要淘汰的脏页数据太多，那flush时间可能会较长】
          3、定时flush
          4、mysql shutdown的时候

          InnoDB刷脏页的策略
          innodb_io_capacity=磁盘的IO能力
          innodb_max_dirty_pages_pct=75%  脏页的上线比例

          脏页比例查询  尽量保持在75%以下
          select VARIABLE_VALUE into @a from global_status where VARIABLE_NAME = 'Innodb_buffer_pool_pages_dirty';
          select VARIABLE_VALUE into @b from global_status where VARIABLE_NAME = 'Innodb_buffer_pool_pages_total';
          select @a/@b;

          innodb_flush_neighbors = 0   0表示只刷自己的数据页，1表示把隔壁的数据页也刷了
          机械硬盘建议 把隔壁的也刷了，SSD的话 刷自己就可以了  Mysql 8 已经是0了（硬件设备越来越高级咯）


          10、为什么删掉了很多数据，表空间一直没小

          mysql8以前 表结构在 .frm中，数据 索引文件再ibdata
          mysql8已经允许吧表结构放在系统数据表中

          表结构数据很小，主要看表数据

          参数 innodb_file_per_table
          表数据可以在共享表空间 也可以是单独的文件
          1、OFF  表数据放在系统共享文件，也就是跟数据字典放在一起
          2、ON 每个InnoDB表数据存储在一个  .ibd为后缀的文件中
          mysql 5.6后 默认值就是on了
          建议是on，容易管理，而且不用tableA了 直接drop table tableA。这个时候会直接回收表空间的。
          如果是放在共享表空间的话，就是表删除了，空间也不会回收

          delete from tableA where id = 1；
          数据行被标记为删除，后面有insert的话，如果B+树定位到这个位置 数据会覆盖。
          如果是数据页被标记为删除，可以复用在任何位置。

          如果insert不是按照所以递增顺序插入的，那么索引的数据页也可能是分裂的

          如果减少这些空洞？
          重建表
          alter table  tableA engine=InnoDB;【在重建表的时候，InnoDB不会把整张表占满，每个页留了1/16给后续的更新用】
          mysql5.5之前流程 创建临时表B 把A中的数据有序的查询来 insert到B，用B替换A，过程中有往A写数据会丢失，且过程中A不允许update。。卧槽 太恐怖了吧
          mysql5.6后引入Online DDL 优化了这个流程
          1、建一个临时文件，扫描A表的所有数据页
          2、用数据页中表A的记录生成B+树，存储到临时文件
          3、生成文件过程中，将所有对A的操作记录 记录在一个文件中 （row log）
          4、临时文件生成后，将日志文件中的操作 应用到临时文件
          5、用临时文件替换A的数据文件



          思考 ：猜想，替换的过程中，应该有并发操作的限制 所以在替换的那一刻，应该lock住表，不能写数据，或者不lock 是双写文件，然后直接替换？？？

          Truncate = drop + create  是会释放表空间的

          11、count（*） 为什么这么慢？
          count(*)的实现方式
          你首先要明确的是，在不同的MySQL引擎中，count(*)有不同的实现方式。
          MyISAM引擎把一个表的总行数存在了磁盘上，因此执行count(*)的时候会直接返回这个数，效率很高；
          而InnoDB引擎就麻烦了，它执行count(*)的时候，需要把数据一行一行地从引擎里面读出来，然后累积计数。
          这里需要注意的是，我们在这篇文章里讨论的是没有过滤条件的count(*)，如果加了where 条件的话，MyISAM表也是不能返回得这么快的。

          innodb的做法，选择索引最小的那颗树，遍历 统计个数

          count(*)  count(1) count(id) count(字段)  如果函数参数不死null就加1 否则不加。
          对于count(主键id)来说，InnoDB引擎会遍历整张表，把每一行的id值都取出来，返回给server层。server层拿到id后，判断是不可能为空的，就按行累加。
          对于count(1)来说，InnoDB引擎遍历整张表，但不取值。server层对于返回的每一行，放一个数字“1”进去，判断是不可能为空的，按行累加。
          单看这两个用法的差别的话，你能对比出来，count(1)执行得要比count(主键id)快。因为从引擎返回id会涉及到解析数据行，以及拷贝字段值的操作。
          对于count(字段)来说：
          如果这个“字段”是定义为not null的话，一行行地从记录里面读出这个字段，判断不能为null，按行累加；
          如果这个“字段”定义允许为null，那么执行的时候，判断到有可能是null，还要把值取出来再判断一下，不是null才累加。
          也就是前面的第一条原则，server层要什么字段，InnoDB就返回什么字段。
          但是count(*)是例外，并不会把全部字段取出来，而是专门做了优化，不取值。count(*)肯定不是null，按行累加。
          所以结论是：按照效率排序的话，count(字段)<count(主键id)<count(1)≈count(*)，所以我建议你，尽量使用count(*)。


          12、order by 是怎么做的？

          explain 执行计划

          select city,name,age from tableA where city = '杭州'  order by name asc limit 100;

          假设存在city的索引

          0 初始化sort_buffer确定放入 city name age三个字段
          1  走city索引，查询数据 city = 杭州 根据主键id
          2、根据id回表把 三个字段取出来方法哦sort_buffer
          3、重复1，2取下一条数据 直到city！=’杭州‘
          4、对sort_buffer按照name做快排
          5、排序结果前100条返回给客户端

          一般来说在sort_buffer内存中完成排序就可以了
          如果需要排序的数据量 > sort_buffer_size
          那么需要借助临时文件来排序了

          数据分别放到多个临时文件中 排序好，然后在合并成一个有序的大文件犯返回

          单行数据太大怎么处理？？排序单行数据量大小设定 max_length_for_sort_data

          如果当行数据大于这个值，那么 我们排序文件中 只存排序字段和id。排序完成后
          最后在根据id主键回表 把数据都查出来。（相比之前的流程 要多一个回表的流程）

          有没有sql是不需要排序的？ 原本就是有序的 哈哈 建一个 city和name的联合索引，那么最开始那个sql就不用sort了

          Using filesort


          13、幻读？
          幻读指的是一个事务在前后两次查询同一个范围的时候，后一次查询看到了前一次查询没有看到的行。

          这里，我需要对“幻读”做一个说明：
          在可重复读隔离级别下，普通的查询是快照读，是不会看到别的事务插入的数据的。因此，幻读在“当前读”下才会出现。
          幻读仅专指“新插入的行”。


          我总结的加锁规则里面，包含了两个“原则”、两个“优化”和一个“bug”。
          原则1：加锁的基本单位是next-key lock。希望你还记得，next-key lock是前开后闭区间。
          原则2：查找过程中访问到的对象才会加锁。
          优化1：索引上的等值查询，给唯一索引加锁的时候，next-key lock退化为行锁。
          优化2：索引上的等值查询，向右遍历时且最后一个值不满足等值条件的时候，next-key lock退化为间隙锁。
          一个bug：唯一索引上的范围查询会访问到不满足条件的第一个值为止。


          14、mysql高可用

          show slave status  可以看从库延迟了多少秒 seconds_behind_master


          重构表，回收表空间
          alter table T engine=InnoDB



          15、死锁分析 https://blog.csdn.net/varyall/article/details/80219459


####  ***

[ 电大学长的mysql锁分析 ] (https://www.aneasystone.com/archives/2017/12/solving-dead-locks-three.html)











