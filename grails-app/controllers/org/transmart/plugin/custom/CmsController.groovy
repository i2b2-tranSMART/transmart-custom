package org.transmart.plugin.custom

import org.springframework.beans.factory.annotation.Autowired

/**
 * @author <a href='mailto:burt_beckwith@hms.harvard.edu'>Burt Beckwith</a>
 */
class CmsController {

	@Autowired private CmsService cmsService

	/**
	 * Called by the link rendered by a &lt;cms:file&gt; tag.
	 *
	 * @param id the CmsFile name
	 */
	def file(String id) {
		cmsService.sendFile id, response
	}
}
