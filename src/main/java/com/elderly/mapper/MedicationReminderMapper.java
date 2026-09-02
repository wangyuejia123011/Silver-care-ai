package com.elderly.mapper;

import com.elderly.entity.MedicationReminder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface MedicationReminderMapper {

    /** 新增 */
    int insert(MedicationReminder reminder);

    /** 更新 */
    int update(MedicationReminder reminder);

    /** 删除 */
    int deleteById(@Param("id") Long id);

    /** 按用户查询全部 */
    List<MedicationReminder> selectByUserId(@Param("userId") Long userId);

    /** 按用户查询已启用项（按服药时间升序） */
    List<MedicationReminder> selectEnabledByUserId(@Param("userId") Long userId);
}
