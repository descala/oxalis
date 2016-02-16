/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.b2brouter;

import java.io.File;


/**
 *
 * @author lluis
 */
public class IngentRepositoryException extends RuntimeException {

    public IngentRepositoryException(File outputFile, Exception e) {
        super("Unable to process " + outputFile + "; " + e, e);
    }

}
