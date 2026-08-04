package com.workbuddy.quicklaunch.data

/**
 * 对 DAO 的轻量封装，方便在 Activity / Receiver 中直接调用。
 */
class AutomationRepository(private val dao: AutomationDao) {
    fun getAll() = dao.getAll()
    fun getById(id: Long) = dao.getById(id)
    fun getEnabledByType(type: String) = dao.getEnabledByType(type)
    fun insert(a: Automation) = dao.insert(a)
    fun update(a: Automation) = dao.update(a)
    fun delete(a: Automation) = dao.delete(a)
}
