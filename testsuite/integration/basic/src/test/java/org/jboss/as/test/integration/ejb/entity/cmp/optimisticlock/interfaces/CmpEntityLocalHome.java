/*
 * Generated by XDoclet - Do not edit!
 */
package org.jboss.as.test.integration.ejb.entity.cmp.optimisticlock.interfaces;

/**
 * Local home interface for CmpEntity.
 */
public interface CmpEntityLocalHome extends javax.ejb.EJBLocalHome {
    CmpEntityLocal create(java.lang.Integer id, java.lang.String stringGroup1, java.lang.Integer integerGroup1, java.lang.Double doubleGroup1, java.lang.String stringGroup2, java.lang.Integer integerGroup2, java.lang.Double doubleGroup2)
            throws javax.ejb.CreateException;

    CmpEntityLocal findById(java.lang.Integer id)
            throws javax.ejb.FinderException;

    CmpEntityLocal findByPrimaryKey(java.lang.Integer pk)
            throws javax.ejb.FinderException;

}
