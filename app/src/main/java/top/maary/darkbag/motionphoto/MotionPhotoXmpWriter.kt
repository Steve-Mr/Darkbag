package top.maary.darkbag.motionphoto

import android.util.Log
import java.io.*

/**
 * Handles XMP metadata injection and MP4 binary payload appending
 * following the Google Motion Photo specification.
 */
object MotionPhotoXmpWriter {
    private const val TAG = "MotionPhotoXmpWriter"
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    /**
     * Builds the Google Motion Photo XMP RDF XML payload.
     *
     * @param presentationTimestampUs The microsecond timestamp of the still frame within the video.
     * @param videoLengthBytes The size in bytes of the appended MP4 video file.
     */
    fun buildXmpPayload(
        presentationTimestampUs: Long,
        videoLengthBytes: Long
    ): String {
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    GCamera:MotionPhoto="1"
                    GCamera:MotionPhotoVersion="1"
                    GCamera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs"
                    GCamera:MicroVideoOffset="$videoLengthBytes">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Item:Mime>image/jpeg</Item:Mime>
                        <Item:Semantic>Primary</Item:Semantic>
                        <Item:Length>0</Item:Length>
                        <Item:Padding>0</Item:Padding>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Item:Mime>video/mp4</Item:Mime>
                        <Item:Semantic>MotionPhoto</Item:Semantic>
                        <Item:Length>$videoLengthBytes</Item:Length>
                        <Item:Padding>0</Item:Padding>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }

    /**
     * Injects the Motion Photo XMP metadata into the JPEG data and appends the MP4 video binary.
     *
     * @param jpegBytes The raw bytes of the still JPEG image.
     * @param mp4File The video file to embed.
     * @param presentationTimestampUs The presentation timestamp of the still image in microseconds.
     * @param outputStream The output stream to write the combined Motion Photo JPEG to.
     */
    fun writeMotionPhoto(
        jpegBytes: ByteArray,
        mp4File: File,
        presentationTimestampUs: Long,
        outputStream: OutputStream
    ) {
        val videoLength = mp4File.length()
        val xmpString = buildXmpPayload(presentationTimestampUs, videoLength)
        val xmpBytes = (XMP_HEADER + xmpString).toByteArray(Charsets.UTF_8)

        // Inject XMP into JPEG
        val jpegWithXmp = injectXmpIntoJpeg(jpegBytes, xmpBytes)

        // Write [JPEG with XMP] + [MP4 bytes]
        outputStream.write(jpegWithXmp)

        FileInputStream(mp4File).use { mp4In ->
            mp4In.copyTo(outputStream)
        }
        outputStream.flush()
        Log.d(TAG, "Successfully generated Motion Photo: JPEG=${jpegWithXmp.size} bytes, MP4=$videoLength bytes")
    }

    /**
     * Injects an APP1 XMP marker segment immediately after the JPEG SOI (or after existing APP markers).
     */
    private fun injectXmpIntoJpeg(jpeg: ByteArray, xmpPayload: ByteArray): ByteArray {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            // Not a valid JPEG, return raw
            return jpeg
        }

        val out = ByteArrayOutputStream(jpeg.size + xmpPayload.size + 10)
        // Write SOI (0xFF, 0xD8)
        out.write(0xFF)
        out.write(0xD8)

        // Write APP1 marker for XMP (0xFF, 0xE1)
        val app1Length = xmpPayload.size + 2
        out.write(0xFF)
        out.write(0xE1)
        out.write((app1Length shr 8) and 0xFF)
        out.write(app1Length and 0xFF)
        out.write(xmpPayload)

        // Write the rest of the original JPEG (skipping original SOI)
        out.write(jpeg, 2, jpeg.size - 2)

        return out.toByteArray()
    }
}
