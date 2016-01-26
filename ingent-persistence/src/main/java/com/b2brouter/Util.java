/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.b2brouter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.codec.binary.Base64OutputStream;

/**
 *
 * @author lluis
 */
public class Util {

    public static String compress_b64(String text) throws IOException {
        OutputStream compressed = new ByteArrayOutputStream();
        Base64OutputStream b64os = new Base64OutputStream(compressed);
        GZIPOutputStream gzip = new GZIPOutputStream(b64os);
        gzip.write(text.getBytes("UTF-8"));
        gzip.close();
        b64os.close();
        return compressed.toString();
    }

}
