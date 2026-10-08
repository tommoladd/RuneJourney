package com.runejourney.cloud;

import java.io.IOException;
import lombok.Getter;

/**
 * The cloud (or the storage behind it) turned a request down.
 */
@Getter
public class CloudException extends IOException
{
	private final int status;
	/**
	 * The API's error code, e.g. "sequence_mismatch", or null when there isn't one.
	 */
	private final String code;
	private final Api.Error details;

	public CloudException(int status, String code, String message, Api.Error details)
	{
		super(message != null ? message : "HTTP " + status);
		this.status = status;
		this.code = code;
		this.details = details;
	}

	public boolean is(String code)
	{
		return code.equals(this.code);
	}

	/**
	 * The key was revoked, or its user deleted.
	 */
	public boolean isKeyRejected()
	{
		return status == 401;
	}

	/**
	 * The exception behind a failed future, unwrapped.
	 */
	static Throwable cause(Throwable t)
	{
		while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
			&& t.getCause() != null)
		{
			t = t.getCause();
		}
		return t;
	}
}
